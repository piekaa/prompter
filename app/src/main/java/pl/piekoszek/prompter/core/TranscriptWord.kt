package pl.piekoszek.prompter.core

/**
 * One recognized word from the transcript.
 * `conf` defaults to 1.0 — SpeechService delivers plain strings, so words carry
 * no per-word confidence; words below [AlignmentConfig.confThreshold] contribute
 * nothing to the score (PROJEKT 5.1).
 */
data class TranscriptWord(
    val text: String,
    val conf: Double = 1.0,
)
