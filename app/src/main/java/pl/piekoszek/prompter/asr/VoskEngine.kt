package pl.piekoszek.prompter.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.concurrent.Executors

/**
 * Thin wrapper over Vosk's [Model] / [Recognizer] / [SpeechService] (PROJEKT 4).
 *
 * Threading (verified against the AAR bytecode):
 *  - [StorageService.unpack] copies the model on its own executor; its
 *    callbacks arrive on the **main** thread.
 *  - [SpeechService] posts every recognition callback on the **main** thread
 *    (main-looper Handler), so [Listener] methods are safe to use from UI.
 *  - [SpeechService.stop] **blocks** (interrupts + joins the recognizer
 *    thread) — it runs on a dedicated IO executor here.
 *
 * Engine operations (start/stop/reset) are dispatched on a single IO
 * thread, which also orders them; state flags are [Volatile].
 */
class VoskEngine(context: Context) {

    /** Recognition events; delivered on the **main** thread. */
    interface Listener {
        /** Live partial result (may still change). */
        fun onPartial(text: String)
        /** Sentence finalized by the endpointer (silence). */
        fun onFinal(text: String)
        /** The stream ended (stop() / timeout); final tail already delivered via onFinal. */
        fun onEnded()
        /** Fatal error (microphone in use, etc.). Listening stopped. */
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vosk-io").apply { isDaemon = true }
    }

    @Volatile private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    /** Model unpacked + loaded (idempotent). */
    @Volatile var ready: Boolean = false
        private set

    /** SpeechService currently listening. */
    @Volatile var running: Boolean = false
        private set

    @Volatile var paused: Boolean = false
        private set

    private var listener: Listener? = null

    /**
     * Copies the PL model from assets (idempotent, uuid-keyed) and loads it.
     * Safe to call any time; [onReady]/[onError] fire on the main thread.
     */
    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        val m = model
        if (m != null) {
            onReady()
            return
        }
        StorageService.unpack(
            appContext,
            MODEL_ASSET,
            MODEL_TARGET,
            { unpacked ->
                model = unpacked
                ready = true
                onReady()
            },
            { err -> onError("Unpacking PL model failed: ${err.message}") },
        )
    }

    /**
     * Starts recognition. `grammarJson` (null = full vocabulary) applies to
     * the fresh recognizer and is re-applied on every start, so the grammar
     * of the current script wins even when the recognizer is reused.
     * Callbacks fire on the main thread.
     */
    fun start(
        grammarJson: String?,
        listener: Listener,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        this.listener = listener
        if (running) {
            onError("Already listening")
            return
        }
        fun startWithModel(m: Model) {
            io.execute {
                try {
                    val rec = recognizer ?: Recognizer(m, RATE, grammarJson).also {
                        it.setEndpointerMode(Recognizer.EndpointerMode.LONG)
                        it.setEndpointerDelays(T_START_MAX_S, T_END_S, T_MAX_S)
                        recognizer = it
                    }
                    if (grammarJson != null) rec.setGrammar(grammarJson)
                    val svc = speech ?: SpeechService(rec, RATE).also { speech = it }
                    if (svc.startListening(recognitionListener)) {
                        running = true
                        paused = false
                        main.post(onStarted)
                    } else {
                        main.post { onError("startListening failed — microphone in use?") }
                    }
                } catch (t: Throwable) {
                    main.post { onError(t.message ?: t.toString()) }
                }
            }
        }

        val m = model
        if (m != null) {
            startWithModel(m)
        } else {
            init(
                onReady = { model?.let(::startWithModel) ?: main.post { onError("Model not ready") } },
                onError = { err -> main.post { onError(err) } },
            )
        }
    }

    /** Pauses/resumes audio capture (recognized partial is kept). */
    fun setPause(pause: Boolean) {
        val svc = speech ?: return
        paused = pause
        svc.setPause(pause)
    }

    /** Discards the in-flight partial utterance (e.g. after a manual jump). */
    fun reset() {
        io.execute {
            speech?.reset()
        }
    }

    /**
     * Stops listening. Blocking internally, so it runs on the IO executor;
     * [onStopped] fires on the main thread after the thread has joined.
     */
    fun stop(onStopped: () -> Unit = {}) {
        if (!running) {
            onStopped()
            return
        }
        io.execute {
            try {
                speech?.stop()
            } catch (t: Throwable) {
                // Thread join issues are non-fatal; state is updated below.
            } finally {
                running = false
                main.post(onStopped)
            }
        }
    }

    /** Releases native resources (call when the app is done, e.g. VM cleared). */
    fun release() {
        io.execute {
            try {
                speech?.shutdown()
            } catch (_: Throwable) {
            }
            try {
                recognizer?.close()
            } catch (_: Throwable) {
            }
            try {
                model?.close()
            } catch (_: Throwable) {
            }
            speech = null
            recognizer = null
            model = null
            ready = false
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(result: String) {
            if (result.isNotEmpty()) listener?.onPartial(result)
        }

        override fun onResult(result: String) {
            if (result.isNotEmpty()) listener?.onFinal(result)
        }

        override fun onFinalResult(result: String) {
            // Final tail after stop() — commit it, then signal the end.
            if (result.isNotEmpty()) listener?.onFinal(result)
            listener?.onEnded()
        }

        override fun onError(exception: Exception) {
            running = false
            listener?.onError(exception.message ?: exception.toString())
        }

        override fun onTimeout() {
            listener?.onEnded()
        }
    }

    companion object {
        const val RATE = 16000f
        /** assets folder holding vosk-model-small-pl (see PROJEKT 3.4). */
        const val MODEL_ASSET = "model-pl"
        /** External target directory for the unpacked model. */
        const val MODEL_TARGET = "model"

        // Endpointing: long pauses tolerated, so the endpointer doesn't chop
        // a sentence mid-thought (PROJEKT 3.3, ANSWER_LONG).
        private const val T_START_MAX_S = 5f
        private const val T_END_S = 1f
        private const val T_MAX_S = 30f
    }
}
