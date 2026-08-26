package pl.piekoszek.prompter.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.piekoszek.prompter.AsrStatus
import pl.piekoszek.prompter.PrompterViewModel
import pl.piekoszek.prompter.core.Normalizer

private const val WORDS_PER_LINE = 10

/**
 * Screen 2 (PROJEKT 6): full-screen teleprompter.
 *
 * - Text split into lines of [WORDS_PER_LINE] words; each line keeps a
 *   minimum fixed height so scroll math is stable (long lines may wrap).
 * - Auto mode: on every committed position change, center the current line —
 *   `animateScrollToItem(line)` + `scrollBy(viewport/2 - lineH/2)`.
 * - Spoken words (index < position) are full color; the rest dimmed.
 *   With "follow partial" enabled the highlight uses the tentative position.
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
        script?.text?.let(Normalizer::words) ?: emptyList()
    }
    val lines = remember(words) { words.chunked(WORDS_PER_LINE) }

    val fontSizeSp = state.settings.fontSizeSp
    val dark = state.settings.darkBackground
    val session = state.session

    val spokenColor = if (dark) Color.White else Color(0xFF141414)
    val dimColor = spokenColor.copy(alpha = 0.35f)
    val background = if (dark) Color.Black else Color(0xFFFAFAF7)

    // Position used for the highlight (committed, or live preview).
    val highlight = if (state.settings.followPartial) session.previewPosition
        else session.position

    val density = LocalDensity.current
    val lineMinHeight = remember(fontSizeSp) { (fontSizeSp * 1.8f).dp }
    var viewportPx by remember { mutableIntStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    /** (firstVisibleItemIndex, scrollOffset) when the current touch started. */
    var touchStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val lazyState = rememberLazyListState()

    // Top padding = 2 line-heights → reading line appears ~2 lines down from
    // visible area edge regardless of screen/orientation/font.
    val topPaddingPx = (lineMinHeight * 2).coerceAtLeast(16.dp)

    // Top padding = 2 line-heights → reading line appears ~2 lines down from
    // visible area edge regardless of screen/orientation/font.
    val topPaddingDp = (lineMinHeight * 2).coerceAtLeast(16.dp)

    LaunchedEffect(session.position, session.previewPosition, session.status) {
        if (!touching && viewportPx > 0 && lines.isNotEmpty()) {
            val target = if (state.settings.followPartial) session.previewPosition else session.position
            val targetLine = (target / WORDS_PER_LINE).coerceIn(0, lines.size - 1)
            lazyState.animateScrollToItem(targetLine)
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
                        // Observe touch down/up on the text area without
                        // consuming events (the list still scrolls natively).
                        // Press pauses auto-scroll; release commits the
                        // visible line ONLY if the list actually moved (a
                        // real drag), so alignment re-anchors where the user
                        // left it and the next word spoken scrolls again.
                        // A bare tap commits nothing; cancel just unpauses.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Press -> {
                                        touching = true
                                        val idx = lazyState.firstVisibleItemIndex
                                        val off = lazyState.firstVisibleItemScrollOffset
                                        touchStart = idx to off
                                    }
                                    PointerEventType.Release -> {
                                        if (touching) {
                                            touching = false
                                            val start = touchStart
                                            touchStart = null
                                            val idx = lazyState.firstVisibleItemIndex
                                            val off = lazyState.firstVisibleItemScrollOffset
                                            if (start != null && start != (idx to off)) {
                                                commitVisibleLine(lazyState, viewportPx, vm)
                                            }
                                        }
                                    }
                                    PointerEventType.Move -> Unit
                                }
                            }
                        }
                    }
                    .onSizeChanged { viewportPx = it.height },
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    Text(
                        text = lineAnnotated(line, index * WORDS_PER_LINE, highlight, spokenColor, dimColor),
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
                }
            }

            // -- status + controls -------------------------------------------------
            Text(
                text = statusText(session.status, session.statusMessage),
                color = dimColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            if (session.liveTranscript.isNotEmpty()) {
                Text(
                    text = session.liveTranscript,
                    color = dimColor,
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
    }
}

/**
 * On touch release: the line whose center is closest to the viewport center
 * becomes the committed position (manual override, PROJEKT 5.4).
 */
private fun commitVisibleLine(
    lazyState: LazyListState,
    viewportPx: Int,
    vm: PrompterViewModel,
) {
    if (viewportPx <= 0) return
    val info = lazyState.layoutInfo
    if (info.totalItemsCount == 0) return
    // Item offsets are absolute (from content start); the viewport center is
    // viewportStartOffset + half the visible height.
    val center = info.viewportStartOffset + viewportPx / 2
    val lineIndex = info.visibleItemsInfo
        .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }
        ?.index
        ?: lazyState.firstVisibleItemIndex
    vm.manualJump(lineIndex * WORDS_PER_LINE)
}

private fun lineAnnotated(
    line: List<String>,
    baseIndex: Int,
    highlight: Int,
    spoken: Color,
    dim: Color,
): AnnotatedString = buildAnnotatedString {
    line.forEachIndexed { i, word ->
        val spokenWord = baseIndex + i < highlight
        withStyle(SpanStyle(color = if (spokenWord) spoken else dim)) {
            append(word)
        }
        if (i < line.size - 1) {
            withStyle(SpanStyle(color = dim)) { append(" ") }
        }
    }
}

private fun statusText(status: AsrStatus, message: String?): String = when (status) {
    AsrStatus.IDLE -> "Gotowy"
    AsrStatus.STARTING -> "Startuję… (pierwszy start: rozpakowywanie modelu)"
    AsrStatus.LISTENING -> "Słucham…"
    AsrStatus.PAUSED -> "Pauza"
    AsrStatus.FINISHED -> "Koniec"
    AsrStatus.ERROR -> "Błąd: ${message ?: "nieznany"}"
}
