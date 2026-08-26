# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Prompter** — Android teleprompter that scrolls itself by listening to what you say (Kotlin + Jetpack Compose, `pl.piekoszek.prompter`, minSdk 24, targetSdk 37). Offline Polish ASR via Vosk (`vosk-model-small-pl`, ~50 MB model shipped in assets, ~92 MB unpacked). `PROJEKT.md` is the design document (in Polish) with the algorithm specs and milestone status — read it before touching `core/` or the ASR pipeline.

## Commands

```bash
./gradlew :app:assembleDebug          # build; APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # all unit tests (pure JVM, seconds)
./gradlew :app:testDebugUnitTest --tests "pl.piekoszek.prompter.core.AlignmentEngineTest"   # single class
```

- All app logic that doesn't need Android lives in `core/` and is tested as plain JUnit — run those tests after any change to alignment/scoring/grammar logic.
- **Do not install the APK automatically** (no `adb install` / MTP push). The user copies the APK to the phone and installs it manually; the device is usually not attached via adb. Just report the APK path after a build.

## Architecture

```
Mic → VoskEngine (SpeechService, bg thread) → callbacks on MAIN thread
     → TranscriptBuf (confirmed + partial words)
     → AlignmentEngine (position p = words spoken, hysteresis)
     → PrompterViewModel UiState → Compose scroll/highlight
     ↘ GrammarBuilder window ±150 words → Recognizer.setGrammar() live reconfig
```

- `core/` — **pure JVM, zero Android deps** (deliberately; unit-tested with fake transcripts): `AlignmentEngine` (the heart), `GrammarBuilder`, `Normalizer`, `Similarity`, `TranscriptWord`. Keep it Android-free.
- `asr/VoskEngine` — Android wrapper over `Model`/`Recognizer`/`SpeechService`; owns threading. `asr/TranscriptBuf` is also pure JVM.
- `PrompterViewModel` — single app-level ViewModel; ALL state flows through one `UiState` StateFlow (scripts + settings + session). No other state holders.
- `MainActivity` — single activity, plain enum navigation (EDITOR / PROMPTER / SETTINGS); no navigation-compose.
- Persistence: scripts in `filesDir/scripts/` (`ScriptStore`), settings as JSON in `filesDir/settings.json` — plain files, no Room.

## Invariants that span multiple files (read before changing)

**Position semantics.** `position` p ∈ [0, n] = number of target words *spoken* (not an index). `GrammarBuilder.window` centers on `words[position-1]`; `AlignmentEngine.scoreAt(i)` compares the last W transcript words against `target[i-W+1..i]`; `progress = p / n`.

**Commit vs preview.** Only `onFinal` (endpointer silence) commits a position via `alignment.updateFinal()` — partials never commit; they only feed `alignment.preview()` for a soft UI hint when `settings.followPartial` is on. `fedCount` in the VM tracks how many confirmed words were already fed (append-only), so words are never re-fed after a manual jump clears the transcript.

**Threading (verified against the AAR bytecode, see VoskEngine.kt header).** `SpeechService` posts all recognition callbacks on the **main** thread — safe to touch UI state directly, no extra synchronization. `speech.stop()` **blocks** (joins the recognizer thread) and `setGrammar` must not race the audio loop — both run on the engine's single-thread `vosk-io` executor, which also serializes start/stop/setGrammar. State flags are `@Volatile`.

**Grammar windowing.** The recognizer vocabulary is a live-swapped window of ±150 words around the current position (`setGrammar`), not the whole script — swapped when `|pos - grammarCenter| > radius/2`. Grammar is a JSON array of words + a single `"[unk]"`; words outside the vocabulary land in `[unk]` and score 0.

**Anti-jitter.** Position only moves forward (monotonic). The best local score must beat the current position's score by a forward margin of 0.15·W, plus a small forward bias. Similarity tiers: exact = 1.0, diacritic-fold match = 0.5, fuzzy (Levenshtein) capped at 0.3, ratio ≥ 0.5 → 0. Don't raise the fuzzy cap or loosen the margin casually — that's what keeps `p` from jittering on a ~12–18% WER Polish model.

**Manual override.** `manualJump(pos)` = commit position + clear alignment transcript + `engine.reset()` (discard in-flight partial) + refresh grammar window. Any new "jump" feature must do all four, or alignment resumes from stale state.

## Quirks

- Vosk deps are declared **`@aar`** form (`com.alphacephei:vosk-android:0.3.75@aar`, `net.java.dev.jna:jna:5.18.1@aar`) — same as the official demo; the AARs carry per-ABI native libs. Don't "fix" this to plain coordinates.
- `SpeechService` delivers **plain strings** — no per-word confidence. `TranscriptWord.conf` defaults to 1.0 and `confThreshold` currently filters nothing real; if you switch to raw `Recognizer` JSON results to get `words[]`/`conf`, that's a deliberate pipeline change.
- Endpointing is tuned for a speaker: `EndpointerMode.LONG` with delays 5 s / 1 s / 30 s so sentences aren't chopped mid-thought.
- The model unpacks once from assets to external storage (`StorageService.unpack`, idempotent, kicked off in the VM `init`); don't delete `app/src/main/assets/model-pl/`.
- Release build has R8/`optimization` disabled — not signed/configured for Play; debug APK is the working artifact.
