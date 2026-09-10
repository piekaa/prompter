package pl.piekoszek.prompter.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import pl.piekoszek.prompter.AsrStatus
import pl.piekoszek.prompter.PrompterViewModel
import pl.piekoszek.prompter.core.Normalizer
import kotlin.math.log

private const val WORDS_PER_LINE = 10

/** Blank line (possibly with spaces/tabs) separating paragraphs. */
private val BLANK_LINE = Regex("\\n[ \\t]*\\n+")

/**
 * One item in the prompter list. [Line] holds a chunk of up to
 * [WORDS_PER_LINE] words; [wordStart] is the index of its first word in the
 * global word list (alignment coordinates). [Break] marks a blank line of
 * the original script — rendered as a vertical gap so paragraph structure
 * stays visible while speaking.
 */
private sealed class PromptRow {
    data class Line(val words: List<String>, val wordStart: Int) : PromptRow()
    data object Break : PromptRow()
}

/**
 * Velocity-based auto-scroll: the last read word is carried back up to the
 * "reading line". Speed ramps linearly with the word's height in the
 * viewport — 0% at [SCROLL_STOP_PCT], 100% at [SCROLL_FULL_PCT] (max speed
 * = settings.maxScrollSpeed, configurable) — so the scroll stops by itself
 * when the word reaches the reading line.
 */
private const val SCROLL_STOP_PCT = 20f
private const val SCROLL_FULL_PCT = 80f

/**
 * Screen 2 (PROJEKT 6): full-screen teleprompter.
 *
 * - Text split into lines of [WORDS_PER_LINE] words; each line keeps a
 *   minimum fixed height so scroll math is stable (long lines may wrap).
 *   Blank lines of the original script survive as [PromptRow.Break] gaps
 *   between the reflowed lines (paragraph structure preserved).
 * - Auto mode: continuous velocity-based scroll — a frame-locked loop calls
 *   `scrollBy(speed · dt)` where speed is 0 when the last read word is at
 *   20% viewport height, full at 80%, linear in between. The word is thus
 *   carried up to the 20% reading line and the scroll stops by itself
 *   (no target, no animation, no jitter). Paused while a finger is down.
 * - Spoken words (index < position) are full white; unspoken words are dimmed
 *   white. With "follow partial" enabled the highlight uses the tentative
 *   position.
 * - Dragging the text re-anchors the reading position: on release a real
 *   drag (list actually moved) commits the visible line (PROJEKT 5.4), so
 *   alignment + grammar window resume where the user left it. There is no
 *   permanent manual mode — auto-follow stays on, and the next committed
 *   word scrolls the text again. A bare tap commits nothing.
 * - While a finger is down, auto-scroll pauses so it never fights the drag;
 *   a pointer cancel only clears the pause (never sticks it).
 */
@Composable
fun PrompterScreen(
    vm: PrompterViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val script = state.current
    val words = remember(script?.id, script?.text) {
        // displayWords keeps original casing + punctuation but has the SAME
        // count/order as Normalizer.words (the alignment token list), so the
        // highlight index stays aligned.
        script?.text?.let(Normalizer::displayWords) ?: emptyList()
    }
    // Split on blank lines, then reflow each paragraph into WORDS_PER_LINE-word
    // rows. Word offsets are global, so highlight/commit indices stay aligned
    // with the alignment engine. (displayWords tokens never cross a blank
    // line — they stop at whitespace — so per-paragraph counts sum exactly.)
    val rows = remember(script?.id, script?.text) {
        val text = script?.text ?: return@remember emptyList<PromptRow>()
        val out = mutableListOf<PromptRow>()
        var sawLine = false
        var offset = 0
        text.split(BLANK_LINE).forEach { para ->
            val chunk = Normalizer.displayWords(para).chunked(WORDS_PER_LINE)
            if (chunk.isEmpty()) return@forEach
            if (sawLine) out += PromptRow.Break
            chunk.forEach { c ->
                out += PromptRow.Line(c, offset)
                offset += c.size
            }
            sawLine = true
        }
        out
    }

    val fontSizeSp = state.settings.fontSizeSp
    val dark = state.settings.darkBackground
    val session = state.session

    // All text is white; spoken words have underline, unspoken don't
    val spokenColor = Color.White
    val background = if (dark) Color.Black else Color(0xFFFAFAF7)

    // Position used for the highlight: now always follows preview position for
    // immediate visual feedback on partial words (followPartial is always on).
    val highlight = session.highlight

    val density = LocalDensity.current
    val lineMinHeight = remember(fontSizeSp) { (fontSizeSp * 1.8f).dp }
    var viewportPx by remember { mutableIntStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    // On-screen debug (top-right overlay): auto-scroll status. Written by the
    // scroll loop below (throttled to ~5 Hz + change-gated so it doesn't
    // force a recomposition every frame).
    var scrollDebug by remember { mutableStateOf("scroll: starting…") }
    val lazyState = rememberLazyListState()

    /**
     * Debug + scroll control: last read word (words[highlight-1]) and its
     * vertical position as a % of the visible viewport height
     * (0% = top, 100% = bottom, null = off-screen). Approximation: uses the
     * line's center, so in a wrapped line it is the middle of the line, not
     * the exact word row.
     *
     * Keep the State object — the scroll loop below reads it per frame so it
     * sees live values (capturing the `.value` local would be stale).
     */
    val lastWordDebugState = remember(lazyState, words, rows) {
        derivedStateOf {
            val h = state.session.highlight
            if (h <= 0) {
                null
            } else {
                val idx = (h - 1).coerceAtMost(words.size - 1)
                // Rows include Break spacers, so find the row by word offset
                // (not by fixed chunk math).
                val lineIdx = rows.indexOfFirst {
                    it is PromptRow.Line && it.wordStart <= idx && idx < it.wordStart + it.words.size
                }
                val info = lazyState.layoutInfo
                val item = if (lineIdx >= 0) {
                    info.visibleItemsInfo.firstOrNull { it.index == lineIdx }
                } else {
                    null
                }
                val vp = info.viewportSize.height
                val percent = if (item != null && vp > 0) {
                    val center = (item.offset - info.viewportStartOffset) + item.size.toFloat() / 2f
                    center / vp * 100f
                } else {
                    null
                }
                LastWordDebug(words[idx], percent)
            }
        }
    }
    val lastWordDebug = lastWordDebugState.value

    // Top padding = 2 line-heights → reading line appears ~2 lines down from
    // visible area edge regardless of screen/orientation/font.
    val topPaddingDp = (lineMinHeight * 2).coerceAtLeast(16.dp)
    val topPaddingPx = with(density) { topPaddingDp.toPx().toInt() }

    // Velocity-based auto-scroll (replaces the old animateScrollToItem):
    // each frame, speed = f(word height) — 0 at 20%, full at 80%, linear in
    // between, clamped outside. The word is thus carried up to the 20%
    // reading line and the scroll stops by itself. Paused while a finger is
    // down so it never fights the drag. dt is clamped so a long frame gap
    // (e.g. app resume) never teleports the list.
    LaunchedEffect(lazyState, words) {
        var lastFrameNanos: Long? = null
        var lastPublish = 0L
        var lastText = ""
        try {
            while (true) {
                var now = 0L
                withFrameNanos { now = it }
                val prev = lastFrameNanos
                lastFrameNanos = now

                // Decide this frame: scroll, or the exact reason not to.
                var delta = 0f
                var offReason: String? = null
                if (prev != null) {
                    when {
                        touching -> offReason = "paused: finger down (touching)"
                        else -> {
                            val pct = lastWordDebugState.value?.percent
                            when {
                                pct == null -> offReason = "paused: no read word on screen (highlight 0 or offscreen)"
                                else -> {
                                    val t = ((pct - SCROLL_STOP_PCT) /
                                        (SCROLL_FULL_PCT - SCROLL_STOP_PCT)).coerceIn(0f, 1f)
                                    if (t <= 0f) {
                                        offReason = "paused: word at ${pct.roundToInt()}% (stop line ${SCROLL_STOP_PCT.toInt()}%)"
                                    } else {
                                        val vpH = lazyState.layoutInfo.viewportSize.height
                                        if (vpH > 0) {
                                            // Live read: setting changes apply mid-scroll.
                                            val dtSec = ((now - prev) / 1_000_000_000f).coerceAtMost(0.1f)
                                            delta = state.settings.maxScrollSpeed * vpH.toFloat() * t * dtSec
                                            try {
                                                lazyState.scroll { scrollBy(delta) }
                                            } catch (e: java.util.concurrent.CancellationException) {
                                                // A higher-priority scroll mutation (user drag,
                                                // or a fling still settling) won this frame.
                                                // This is the JDK CancellationException, NOT the
                                                // coroutine one — safe to swallow: skip the frame,
                                                // retry next. Letting it propagate would kill the
                                                // whole loop (the original "loop dies" bug).
                                                offReason = "skipped: gesture has higher scroll priority"
                                            }
                                        } else {
                                            offReason = "paused: viewport height 0"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Publish the on-screen debug (~5 Hz, change-gated). The counter
                // is a heartbeat: if it stops ticking the loop itself is dead.
                if (now - lastPublish >= 200_000_000L) {
                    lastPublish = now
                    val heartbeat = (now / 200_000_000L) % 1000
                    val text = if (offReason == null) {
                        "scroll ON  Δ=${String.format(java.util.Locale.US, "%.1f", delta)}px/f  #$heartbeat"
                    } else {
                        "scroll OFF  $offReason  #$heartbeat"
                    }
                    if (text != lastText) {
                        lastText = text
                        scrollDebug = text
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PROMPTER", "LOOP EXCEPTION ${e::class.java.name}", e)
        } finally {
            Log.d("PROMPTER", "LOOP CANCELLED — composition gone")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            // Keep text and controls out of the status/nav bar corners.
            .systemBarsPadding(),
    ) {
        if (script == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nie wybrano skryptu.", color = spokenColor)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("← Do edytora") }
            }
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyState,
                contentPadding = PaddingValues(top = topPaddingDp, bottom = 16.dp),
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(lazyState) {
                        // Observe press/release on the text area without
                        // consuming events (the list still scrolls natively).
                        // Press pauses auto-scroll; release commits the
                        // visible line ONLY if the list actually moved (a
                        // real drag), so alignment re-anchors where the user
                        // left it and the next word spoken scrolls again.
                        // A bare tap commits nothing.
                        //
                        // A gesture cancelled by the system (edge/back swipe,
                        // a notification stealing the touch, finger slipping
                        // off the sensor) delivers NEITHER a Release NOR a
                        // coroutine cancel: it resumes the loop with a
                        // synthetic non-Release event (see
                        // SuspendingPointerInputFilter.onCancelPointerInput —
                        // it dispatches a cancel event with the change
                        // unpressed). Any non-Press/non-Move/non-Release
                        // event therefore ends the gesture: clear the pause,
                        // never commit. (Older code swallowed those in
                        // `else -> Unit`, leaving `touching` true forever —
                        // auto-scroll dead until the next release.)
                        // `finally` still covers disposal-time cancellation.
                        awaitPointerEventScope {
                            var start: Pair<Int, Int>? = null
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()

                                    when (event.type) {
                                        PointerEventType.Press -> {
                                            touching = true
                                            start = lazyState.firstVisibleItemIndex to
                                                lazyState.firstVisibleItemScrollOffset
                                        }
                                        PointerEventType.Move -> Unit
                                        PointerEventType.Release -> {
                                            if (touching) {
                                                touching = false
                                                val s = start
                                                start = null
                                                if (s != null) {
                                                    val end = lazyState.firstVisibleItemIndex to
                                                        lazyState.firstVisibleItemScrollOffset
                                                    if (s != end) {
                                                        commitVisibleLine(lazyState, viewportPx, rows, vm)
                                                    }
                                                }
                                            }
                                        }
                                        else -> {
                                            // Gesture cancel (synthetic event) or
                                            // other non-drag terminations: end
                                            // the pause, never commit a jump.
                                            touching = false
                                            start = null
                                        }
                                    }
                                }
                            } finally {
                                touching = false
                            }
                        }
                    }
                    .onSizeChanged { viewportPx = it.height },
            ) {
                itemsIndexed(rows, key = { index, _ -> index }) { _, row ->
                    when (row) {
                        is PromptRow.Line -> Text(
                            text = lineAnnotated(row.words, row.wordStart, highlight, spokenColor),
                            fontSize = fontSizeSp.sp,
                            // Explicit pitch: the theme's LocalTextStyle carries a
                            // fixed lineHeight (24sp) which would collapse wrapped
                            // lines into each other at larger font sizes.
                            lineHeight = (fontSizeSp * 1.3f).sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = lineMinHeight)
                                .padding(horizontal = 28.dp, vertical = 6.dp),
                        )
                        is PromptRow.Break -> Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((fontSizeSp * 0.5f).dp),
                        )
                    }
                }
            }

            // -- status + controls -------------------------------------------------
            Text(
                text = statusText(session.status, session.statusMessage),
                color = spokenColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            if (session.liveTranscript.isNotEmpty()) {
                Text(
                    text = session.liveTranscript,
                    color = spokenColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .heightIn(max = 48.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val active = session.status == AsrStatus.LISTENING ||
                    session.status == AsrStatus.PAUSED
                Button(
                    onClick = { if (active) vm.stopSession() else vm.startSession() },
                    enabled = !session.finished || !active,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dark) Color(0xFF2E2E2E) else Color(0xFFE0E0E0),
                        contentColor = spokenColor,
                    ),
                ) {
                    Text(if (active) "Stop" else "Start")
                }
                if (active) {
                    TextButton(onClick = { vm.togglePause() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (session.status == AsrStatus.PAUSED) "Wznów" else "Pauza")
                    }
                }
                TextButton(onClick = { vm.changeFont(-2) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("A−")
                }
                TextButton(onClick = { vm.changeFont(+2) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("A+")
                }
                TextButton(onClick = { vm.jumpToEnd() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("⏭")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("←")
                }
            }
            LinearProgressIndicator(
                progress = { session.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Debug overlay (top-right): last read word + its position in % of
        // screen height (0% top … 100% bottom), plus live auto-scroll status
        // (ON with per-frame delta, or OFF with the exact reason: finger
        // down / no read word / above the stop line / zero viewport).
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            lastWordDebug?.let { (word, percent) ->
                val pct = percent?.roundToInt()
                Text(
                    text = "dbg: «$word» ${if (pct != null) "$pct%" else "offscreen"}",
                    color = Color(0xFFFFE082),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = scrollDebug,
                color = if (scrollDebug.startsWith("scroll ON")) {
                    Color(0xFFB9F6CA) // greenish = running
                } else {
                    Color(0xFFFFE082) // amber = stopped, see reason
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * On touch release: the text line whose center is closest to the viewport
 * center becomes the committed position (manual override, PROJEKT 5.4).
 * Break spacers are skipped — the jump target is the line's first word.
 */
private fun commitVisibleLine(
    lazyState: LazyListState,
    viewportPx: Int,
    rows: List<PromptRow>,
    vm: PrompterViewModel,
) {
    if (viewportPx <= 0) return
    val info = lazyState.layoutInfo
    if (info.totalItemsCount == 0) return
    // Item offsets are absolute (from content start); the viewport center is
    // viewportStartOffset + half the visible height.
    val center = info.viewportStartOffset + viewportPx / 2
    val line = info.visibleItemsInfo
        .map { v -> v to rows.getOrNull(v.index) }
        .filter { it.second is PromptRow.Line }
        .minByOrNull { (v, _) -> kotlin.math.abs(v.offset + v.size / 2 - center) }
        ?.second as? PromptRow.Line
        ?: return
    vm.manualJump(line.wordStart)
}

/**
 * Span style for spoken words: white with underline.
 * Unspoken words are white without underline.
 */
private val SpokenStyle = SpanStyle(
    color = Color.White,
    textDecoration = TextDecoration.Underline
)

private fun lineAnnotated(
    line: List<String>,
    baseIndex: Int,
    highlight: Int,
    spoken: Color,
): AnnotatedString = buildAnnotatedString {
    line.forEachIndexed { i, word ->
        val spokenWord = baseIndex + i < highlight
        withStyle(if (spokenWord) SpokenStyle else SpanStyle(color = Color.White)) {
            append(word)
        }
        if (i < line.size - 1) {
            withStyle(SpanStyle(color = Color.White)) { append(" ") }
        }
    }
}

/** Last read word + its height position in % of the viewport (null = off-screen). */
private data class LastWordDebug(val word: String, val percent: Float?)

private fun statusText(status: AsrStatus, message: String?): String = when (status) {
    AsrStatus.IDLE -> "Gotowy"
    AsrStatus.STARTING -> "Startuję… (pierwszy start: rozpakowywanie modelu)"
    AsrStatus.LISTENING -> "Słucham…"
    AsrStatus.PAUSED -> "Pauza"
    AsrStatus.FINISHED -> "Koniec"
    AsrStatus.ERROR -> "Błąd: ${message ?: "nieznany"}"
}
