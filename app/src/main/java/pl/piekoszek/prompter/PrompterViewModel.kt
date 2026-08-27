package pl.piekoszek.prompter

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import pl.piekoszek.prompter.asr.TranscriptBuf
import pl.piekoszek.prompter.asr.VoskEngine
import pl.piekoszek.prompter.core.AlignmentEngine
import pl.piekoszek.prompter.core.GrammarBuilder
import pl.piekoszek.prompter.core.Normalizer
import pl.piekoszek.prompter.core.TranscriptWord
import pl.piekoszek.prompter.scripts.Script
import pl.piekoszek.prompter.scripts.ScriptStore
import java.io.File

/** ASR/session lifecycle as seen by the UI. */
enum class AsrStatus { IDLE, STARTING, LISTENING, PAUSED, FINISHED, ERROR }

/** User-tunable parameters (persisted). */
data class Settings(
    val fontSizeSp: Int = PrompterViewModel.MIN_FONT_SP,
    val darkBackground: Boolean = true,
    /** Words below this confidence contribute nothing to alignment. */
    val confThreshold: Float = 0.5f,
    /** Hysteresis factor (forward moves): margin = factor · W. */
    val margin: Float = 0.15f,
    /** Max auto-scroll speed: fraction of viewport height per second. */
    val maxScrollSpeed: Float = PrompterViewModel.DEFAULT_SCROLL_SPEED,
)

data class SessionState(
    val status: AsrStatus = AsrStatus.IDLE,
    val statusMessage: String? = null,
    /** Committed position: words spoken (AlignmentEngine semantics). */
    val position: Int = 0,
    /** Tentative position including the live partial (not committed). */
    val previewPosition: Int = 0,
    /** 0..1 through the script. */
    val progress: Float = 0f,
    /** True once position reached the end of the script. */
    val finished: Boolean = false,
    /** Short view of the recent transcript for the status line. */
    val liveTranscript: String = "",
    /** Cumulative highlight: words with index < highlight are spoken. */
    val highlight: Int = 0,
)

data class UiState(
    val scripts: List<Script> = emptyList(),
    /** Script selected for the prompter screen (null = none). */
    val current: Script? = null,
    val settings: Settings = Settings(),
    val session: SessionState = SessionState(),
)

/**
 * Single app-level view model: scripts, settings and the prompter session
 * (PROJEKT 4/6). All state flows through [UiState]; all engine callbacks
 * arrive on the main thread (see [VoskEngine]), so no extra synchronization.
 */
class PrompterViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScriptStore(File(app.filesDir, "scripts"))
    private val engine = VoskEngine(app)

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var alignment = buildAlignmentEngine()
    /** Target words of the current session (normalized). */
    private var currentWords: List<String> = emptyList()
    /** Center of the grammar window currently loaded into the recognizer. */
    private var grammarCenter = 0
    /** Transcript buffer for live transcript display. */
    private val buf = TranscriptBuf()

    init {
        // Kick off the one-time model unpack early (idempotent); scripts are
        // already loaded in the initial state.
        engine.init(onReady = {}, onError = { msg ->
            update { it.copy(session = it.session.copy(
                status = AsrStatus.ERROR, statusMessage = msg,
            )) }
        })
    }

    // -- scripts -------------------------------------------------------------

    fun refreshScripts() {
        update { it.copy(scripts = store.list()) }
    }

    fun saveScript(title: String, text: String) {
        if (text.isBlank()) return
        val s = Script(
            id = store.newId(),
            title = title.trim().ifEmpty { "Bez tytułu" },
            text = text,
            updatedAt = System.currentTimeMillis(),
        )
        store.save(s)
        refreshScripts()
    }

    fun deleteScript(id: String) {
        store.delete(id)
        refreshScripts()
    }

    /** Selects the script for the prompter screen (editor "open"). */
    fun openScript(id: String) {
        val s = store.load(id) ?: return
        update { it.copy(current = s) }
    }

    fun clearCurrent() {
        update { it.copy(current = null) }
    }

    // -- session -------------------------------------------------------------

    fun startSession() {
        val st = _state.value
        val script = st.current
        if (script == null) return
        if (ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            update { it.copy(session = it.session.copy(
                status = AsrStatus.ERROR, statusMessage = "Brak zgody: mikrofon",
            )) }
            return
        }
        if (st.session.status == AsrStatus.STARTING || st.session.status == AsrStatus.LISTENING ||
            st.session.status == AsrStatus.PAUSED
        ) return
        val words = Normalizer.words(script.text)
        if (words.isEmpty()) {
            update { it.copy(session = it.session.copy(
                status = AsrStatus.ERROR, statusMessage = "Pusty tekst",
            )) }
            return
        }
        currentWords = words
        grammarCenter = 0
        buf.reset()
        alignment = buildAlignmentEngine()
        alignment.setTarget(words)
        update { it.copy(session = SessionState(status = AsrStatus.STARTING)) }
        val grammar = GrammarBuilder.toJson(GrammarBuilder.window(words, 0))
        engine.start(
            grammarJson = grammar,
            listener = engineListener,
            onStarted = {
                update { it.copy(session = it.session.copy(
                    status = AsrStatus.LISTENING,
                )) }
            },
            onError = { msg ->
                update { it.copy(session = it.session.copy(
                    status = AsrStatus.ERROR, statusMessage = msg,
                )) }
            },
        )
    }

    fun stopSession() {
        val s = _state.value.session
        if (s.status == AsrStatus.IDLE || s.status == AsrStatus.ERROR) return
        engine.stop {
            update { it.copy(session = it.session.copy(
                status = if (it.session.finished) AsrStatus.FINISHED else AsrStatus.IDLE,
            )) }
        }
    }

    fun togglePause() {
        when (_state.value.session.status) {
            AsrStatus.LISTENING -> {
                engine.setPause(true)
                update { it.copy(session = it.session.copy(status = AsrStatus.PAUSED)) }
            }
            AsrStatus.PAUSED -> {
                engine.setPause(false)
                update { it.copy(session = it.session.copy(status = AsrStatus.LISTENING)) }
            }
            else -> Unit
        }
    }

    /**
     * Re-anchor to a user-scrolled position (PROJEKT 5.4): commit
     * [wordIndex] as the position, clear the alignment transcript, flush the
     * recognizer partial, refresh the grammar window. Auto-follow is NOT
     * switched off — the next committed word scrolls the text from here.
     */
    fun manualJump(wordIndex: Int) {
        val words = currentWords
        if (words.isEmpty()) return
        val pos = wordIndex.coerceIn(0, words.size)
        alignment.resetTo(pos)
        buf.reset()
        engine.reset()
        updateGrammarWindow(pos)
        update { it.copy(session = it.session.copy(
            position = pos,
            previewPosition = pos,
            progress = alignment.progress,
            highlight = 0,
        )) }
    }

    fun jumpToEnd() {
        manualJump(currentWords.size)
    }

    // -- settings ------------------------------------------------------------

    fun changeFont(delta: Int) {
        setFontSize(_state.value.settings.fontSizeSp + delta)
    }

    fun setFontSize(sp: Int) {
        setSettings { it.copy(fontSizeSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    }

    fun toggleBackground() {
        setSettings { it.copy(darkBackground = !it.darkBackground) }
    }

    fun setConfThreshold(value: Float) {
        setSettings { it.copy(confThreshold = value) }
        rebuildAlignment()
    }

    fun setMargin(value: Float) {
        setSettings { it.copy(margin = value) }
        rebuildAlignment()
    }

    fun setMaxScrollSpeed(value: Float) {
        setSettings {
            it.copy(maxScrollSpeed = value.coerceIn(MIN_SCROLL_SPEED, MAX_SCROLL_SPEED))
        }
    }

    /** Applies a settings change + persists (no-op if unchanged). */
    private fun setSettings(transform: (Settings) -> Settings) {
        val cur = _state.value
        val next = transform(cur.settings)
        if (next == cur.settings) return
        _state.value = cur.copy(settings = next)
        persistSettings()
    }

    // -- engine callbacks (main thread) ---------------------------------------

    private val engineListener = object : VoskEngine.Listener {
        override fun onPartial(text: String) {
            // Partial contains all words spoken so far (live, may still change).
            // We use this directly for alignment - no separate "final" handling.
            val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (words.isEmpty()) return

            // Feed all words to alignment for position tracking
            val transcriptWords = words.map { TranscriptWord(it) }
            val newPos = alignment.updateForPartial(transcriptWords)

            // Update cumulative highlight (words never "unhighlight")
            val currentHighlight = _state.value.session.highlight
            val nextHighlight = currentHighlight.coerceAtLeast(newPos)

            val targetWords = currentWords
            update { it.copy(session = it.session.copy(
                position = newPos,
                previewPosition = newPos,
                highlight = nextHighlight,
                progress = if (targetWords.isEmpty()) 0f else (newPos.toFloat() / targetWords.size).coerceIn(0f, 1f),
                finished = newPos >= targetWords.size,
            )) }

            updateLiveTranscript(words)
        }

        /** No-op: final results not used in partial-only mode. */
        override fun onFinal(text: String) {
            // Intentionally empty - we only use partial results for alignment.
        }

        override fun onEnded() {
            // Stream closed by stop(); stopSession() owns the state change.
        }

        override fun onError(message: String) {
            update { it.copy(session = it.session.copy(
                status = AsrStatus.ERROR, statusMessage = message,
            )) }
        }
    }

    /** Swaps the recognizer vocabulary for the window around [pos] (PROJEKT 8). */
    private fun updateGrammarWindow(pos: Int) {
        val words = currentWords
        if (words.isEmpty()) return
        if (kotlin.math.abs(pos - grammarCenter) > GrammarBuilder.DEFAULT_RADIUS / 2) {
            grammarCenter = pos
            engine.setGrammar(GrammarBuilder.toJson(GrammarBuilder.window(words, pos)))
        }
    }

    private fun updateLiveTranscript(words: List<String> = emptyList()) {
        // Use provided words if available (from onPartial), otherwise get from transcript buffer
        val recent = if (words.isNotEmpty()) {
            words.takeLast(16).joinToString(" ")
        } else {
            // Fallback: get words from transcript buffer if still needed elsewhere
            val recentWords = buf.recentWords
            if (recentWords.isNotEmpty()) recentWords.takeLast(16).joinToString(" ")
            else ""
        }
        update { it.copy(session = it.session.copy(liveTranscript = recent)) }
    }

    // -- internals -------------------------------------------------------------

    private fun buildAlignmentEngine(): AlignmentEngine {
        val s = _state.value.settings
        return AlignmentEngine(
            AlignmentEngine.AlignmentConfig(
                confThreshold = s.confThreshold.toDouble(),
                forwardMargin = s.margin.toDouble(),
            )
        )
    }

    /** Re-applies alignment config after threshold changes mid-session. */
    private fun rebuildAlignment() {
        if (currentWords.isEmpty()) return
        val engine2 = buildAlignmentEngine()
        engine2.setTarget(currentWords)
        engine2.resetTo(_state.value.session.position)
        alignment = engine2
    }

    private fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    // -- settings persistence ----------------------------------------------------

    private fun loadInitialState(): UiState {
        val f = File(getApplication<Application>().filesDir, SETTINGS_FILE)
        val settings = if (f.exists()) {
            runCatching {
                val j = JSONObject(f.readText())
                Settings(
                    // Coerce: values saved under the old (smaller) range are
                    // migrated up to the new minimum.
                    fontSizeSp = j.optInt("fontSizeSp", MIN_FONT_SP)
                        .coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                    darkBackground = j.optBoolean("darkBackground", true),
                    confThreshold = j.optDouble("confThreshold", 0.5).toFloat(),
                    margin = j.optDouble("margin", 0.15).toFloat(),
                    maxScrollSpeed = j.optDouble(
                        "maxScrollSpeed", DEFAULT_SCROLL_SPEED.toDouble()
                    ).toFloat(),
                )
            }.getOrDefault(Settings())
        } else {
            Settings()
        }
        return UiState(scripts = store.list(), settings = settings)
    }

    private fun persistSettings() {
        val s = _state.value.settings
        runCatching {
            File(getApplication<Application>().filesDir, SETTINGS_FILE).writeText(
                JSONObject()
                    .put("fontSizeSp", s.fontSizeSp)
                    .put("darkBackground", s.darkBackground)
                    .put("confThreshold", s.confThreshold.toDouble())
                    .put("margin", s.margin.toDouble())
                    .put("maxScrollSpeed", s.maxScrollSpeed.toDouble())
                    .toString()
            )
        }
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }

    companion object {
        const val MIN_FONT_SP = 72
        const val MAX_FONT_SP = 144
        /** Auto-scroll max speed range: viewport height fractions per second. */
        const val MIN_SCROLL_SPEED = 0.1f
        const val MAX_SCROLL_SPEED = 1f
        const val DEFAULT_SCROLL_SPEED = 0.3f
        private const val SETTINGS_FILE = "settings.json"
        /** WHITESPACE regex for splitting text into words. */
        private val WHITESPACE = Regex("\\s+")
    }
}
