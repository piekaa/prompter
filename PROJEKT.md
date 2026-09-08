# Prompter — głosowy teleprompter z auto-przewijaniem

Aplikacja mobilna (Android) działająca jako teleprompter, który **sam się przewija,
słuchając, co mówisz**. Mówisz na głos, rozpoznanie mowy (Vosk, offline, na urządzeniu)
śledzi Twoją pozycję w tekście i przewija ekran tak, żebyś zawsze patrzył na obecną
linię.

Data: 2026-08-26 · Status: research / projektowanie

---

## 1. Po co

Zwykły teleprompter przewija po stałym czasie — nie reaguje na to, czy mówisz
szybciej, wolniej, powtórzyłeś zdanie, czy odpuściłeś fragment. Ten prompter
reaguje na **mowę**: pozycja w tekście wyliczana jest z nażywego transcriptu.

Wymagania:
- offline (brak chmury, brak opłat, działa w samolocie),
- polski,
- latencja odczuwalna < ~1 s między "powiedziałem słowo" a "ekran doszedł",
- manual override w każdej chwili (przeciągnij tekst ręką → tryb ręczny).

## 2. Stack

Projekt już istnieje w tym repo: **Android + Kotlin + Jetpack Compose**
(`pl.piekoszek.prompter`, minSdk 24, targetSdk 37).

| Warstwa | Wybór |
|---|---|
| ASR | **Vosk** (`com.alphacephei:vosk-android:0.3.75@aar` + `net.java.dev.jna:jna:5.18.1@aar`) |
| Model PL | `vosk-model-small-pl-0.22` — **50 MB**, Apache-2.0 |
| Śledzenie pozycji | własny moduł alignment (patrz §5) |
| UI | Compose (już w projekcie) |
| Audio | 16 kHz, mono, PCM 16-bit — obsługuje `SpeechService` z Voska |

Dlaczego Vosk, a nie Whisper/Google Speech:
- **streaming z zerowym opóźnieniem** — `PartialResult` w trakcie mówienia (Whisper na telefonie działa w oknach, nie w strumieniu),
- **dynamiczny słownik (grammar)** — killer feature, patrz §4,
- w 100% offline, ~50 MB model, działa na zwykłym telefonie,
- gotowe, stabilne bindingi Android (JNA, bez NDK w projekcie).

## 3. Fakty o Vosku (zweryfikowane w repo i docs)

Źródła: `github.com/alphacep/vosk-api`, `github.com/alphacep/vosk-android-demo`,
`alphacephei.com/vosk/models`.

### 3.1 API Android (z `android/` w vosk-api)

```java
// init (np. po zezwoleniu RECORD_AUDIO):
StorageService.unpack(context, "model-pl", "model",
    model -> { this.model = model; /* gotowe */ },
    err  -> { /* błąd */ });

// start:
Recognizer rec = new Recognizer(model, 16000f);          // bez ograniczeń
// lub ze słownikiem:
Recognizer rec = new Recognizer(model, 16000f, grammarJson);
speechService = new SpeechService(rec, 16000f);
speechService.startListening(listener);   // listener = RecognitionListener

// sterowanie:
speechService.setPause(true);             // pauza (demo ma taki przycisk)
speechService.stop();
```

`RecognitionListener` (callbacki przychodzą **na main thread** — `SpeechService`
internalnie używa `Handler(Looper.getMainLooper())`, więc można od razu ruszać UI):
- `onPartialResult(String)` — **żywy**, może się jeszcze zmienić,
- `onResult(String)` — zdanie sfinalizowane po ciszy,
- `onFinalResult(String)` — koniec strumienia,
- `onError(Exception)`, `onTimeout()`.

### 3.2 Grammar — ograniczenie słownika (kluczowe!)

Z nagłówka C (`src/vosk_api.h`) i Java `Recognizer.java`:

```c
vosk_recognizer_new_grm(model, 16000, grammar)  // tworzenie
vosk_recognizer_set_grm(recognizer, grammar)    // PRZEKONFIGUROWANIE W LOCIE
```

- Grammar = **JSON tablica fraz (stringów)**, np.
  `["witam swiecie", "one zero zero zero one", "[unk]"]`
  (z `vosk-android-demo/VoskActivity.java`).
- `"[unk]"` = dozwól słowa spoza słownika.
- `setGrammar("[]")` wraca do pełnego modelu.
- Wynik rozpoznawania to sekwencja fraz ze słownika → słowa spoza tekstu
  wpadają do `[unk]` albo są wybranie z najbliższej frazy.
- **Dokumentacja modeli**: „most small models allow dynamic vocabulary
  reconfiguration" — modele small (w tym PL) grammar **wsparciają**.
- Efekty: mniej błędnych słów (model nie „halucynuje" poza słownik), szybsze
  działanie, łatwiejsze mapowanie słowa→pozycja.

### 3.3 Inne przydatne API

- `setWords(true)` / `setPartialWords(true)` — w wyniku JSON dodatkowo `words[]`
  z `start`/`end`/`conf` (timestampy per słowo — przydatne do debugu i scoringu).
- `setAlternatives(n)` — n-best z confidence (`"alternatives":[{"text","confidence"}]`).
- `setEndpointerMode(...)` / `setEndpointerDelays(t_start_max, t_end, t_max)` —
  kontrola, kiedy „zdanie się zamyka" (cisza → `onResult`). Dla promptera:
  tryb `ANSWER_LONG`/`VERY_LONG`, żeby nie zrywać myśli.
- `reset()` — czysty start (np. po ręcznym skoku w tekście).
- `vosk-model-small-pl-0.22`: WER **11.55% (Voxpopuli)** / 16.88% (MLS) /
  18.36% (CV test). Mały model ≈ 300 MB RAM, real-time na współczesnym telefonie.

### 3.4 Model PL — rozmiar i dystrybucja

Tylko **jeden** oficjalny model polski: `vosk-model-small-pl-0.22` (50 MB).

Dystrybucja w APK — dwie drogi:
1. **assets + `StorageService.unpack`** (jak demo): APK +50 MB, model od startu.
   Proste, offline, zero sieci. Rekomendowane na start.
2. **download przy pierwszym starcie** z `alphacephei.com/vosk/models` do
   `filesDir`, potem `new Model(path)`. APK mały, ale wymaga sieci 1× i UI
   postępu. Dość (faza M4).

## 4. Architektura

```
┌────────────┐   16 kHz PCM   ┌──────────────┐   partial/final    ┌───────────────┐
│   MIKROFON │ ──────────────▶│ SpeechService│ ──────────────────▶ │ TranscriptBuf │
│ (Android   │                │  + Recognizer│   (main thread)     │  (potwierdzone│
│  AudioRec) │                │  (Vosk, bg)  │                     │   słowa + conf)│
└────────────┘                └──────────────┘                     └──────┬────────┘
                                  ▲  grammar (pełny tekst)             ▼
                                  │                            ┌───────────────────┐
                            ┌─────┴───────────────────────┐     │  AlignmentEngine │
                            │  GrammarBuilder             │◀────│  pozycja w tekście│
                            │  słowa/frazy z całego tekstu│     │  (DP + hysteresis)│
                            └─────────────────────────────┘     └───────┬───────────┘
                                                                        ▼
                                                          ┌──────────────────────────┐
                                                          │  ScrollController (UI)   │
                                                          │  highlight, progress,    │
                                                          │  follow / manual mode    │
                                                          └──────────────────────────┘
```

Komponenty (Kotlin):
- `VoskEngine` — wrapper nad `Model`/`Recognizer`/`SpeechService`; init, start/stop,
  pauza; wystawia `Flow<Partial>` / `Flow<Final>`.
- `GrammarBuilder` — tekst → lista fraz (słowa) + `[unk]`; cały słownik tekstu
  wczytany raz na start sesji (bez podmiany w locie).
- `AlignmentEngine` — czysta, testowalna logika: `(targetText, transcript) → position`.
  Zero zależności od Androida → testy jednostkowe z fikcyjnymi transcriptami.
- `ScrollController` — pozycja → offset przewinięcia + easing; tryb follow/manual.
- `PrompterScreen` (Compose) — tekst, linia czytania, highlight, pasek postępu,
  przyciski (start/pauza, speed, font).

## 5. Algorytm śledzenia pozycji (serce projektu)

Problem: mam celowy tekst `T` (słowa `t[0..n]`) i rosnący, **zakłócony** transcript
`s[0..m]` (błędy ASR, powtórzenia, „mmm", pominięcia). Wyznaczyć `p` — ile słów
w `T` zostało wypowiedzianych.

### 5.1 Normalizacja (obie strony!)

- lowercase, usuń interpunkcję, scal spacje,
- **diakrytyki**: zostaw (Vosk PL je wypisuje), ale scoring tolerancyjny —
  traktuj `ł≈l`, `ą≈a`, `ć≈c` jako bliskie (soft penalty w Levenshteinie, nie hard).
- tokeny `[unk]` / słowo o `conf < próg` (np. 0.5) → niski wagę w scoringu.

### 5.2 Scoring okna (rekomendowane — proste i wystarczająco dokładne)

Sliding window: dla kandydackiej pozycji `i` porównaj ostatnie `W` słów transcriptu
(np. W=6) z `T[i-W+1..i]`:

```
score(i) = Σ_j  sim(s[j], t[i - W + j])        # sim ∈ [0,1]
sim(a,b)  = 1.0  jeśli a == b
          = 0.5  jeśli a == b po de-diakrytyzacji
          = 1 - levenshtein(a,b)/max(len)  inaczej   # z dachem np. 0.3
          = 0.0  jeśli a == [unk] (albo conf < próg)
```

- Szukaj optimum **lokalnie**: w oknie `p_prev ± K` (K≈40) — wystarczająco na tempo
  mówienia (~2-3 słowa/s) i tanio (K·W operacji per update).
- **Hysteresis / anti-jitter**: zmień `p` tylko jeśli `score(i*) > score(p_prev) + margin`
  (np. margin = 0.15·W). Pozycja porusza się tylko w przód.
- Monotonic bias: lekki bonus za `i ≥ p_prev` (mówienie płynie do przodu).
- `p` → % tekstu → offset przewinięcia.

### 5.3 Opcja B: grammar = okna fraz (mapping 1:1)

Zamiast słów — frazy po 4-8 słów (niepokrywające się okna):
`["witam was wszystkich", "dzis omowimy trzy", ..., "[unk]"]`.
Każde rozpoznanie to fraza → bezpośredni indeks → pozycja. Bardziej deterministyczne,
ale słabsze przy błędach na granicach fraz. **Rozstrzygnąć w M2 testem A/B**;
start od 5.2 (słowa) — więcej danych, więcej kontroli.

### 5.4 Co to daje w praktyce

- mówiłeś 120 słów, ASR pomyliło 10 → `p` i tak ≈ 120,
- powtórzyłeś zdanie → transcript dłuższy, ale okno wciąż dopasowuje się do `T`,
- cisza 5 s → `onResult` zamyka zdanie, `p` stabilny, ekran nie drży (hysteresis),
- ręcznie przewinięto ekran → `p` = pozycja z UI (reset alignmentu); auto-follow
  się nie wyłącza — następne wypowiedziane słowo znów przewija tekst.

## 6. UX / ekrany

1. **Editor** — wklej/wgraj tekst (paste, plik `.txt`), podgląd, zapis wielu skryptów.
2. **Prompter** — pełny ekran, tekst, linia czytania (center rule), podświetlenie
   wypowiedzianych słów (np. 70% jaśniejsze), pasek postępu, przyciski:
   start/stop, pauza, +/− font, prędkość (dla trybu manualnego follow),
   „do końca" (skok).
3. **Ustawienia** — model (rozmiar), progi (margin, conf), kolor tła (czerń/biel),
   auto-lock off (ekran zawsze na górze), do-not-disturb w trakcie.

Zasady:
- przewijanie tylko na **final result** (stabilne) + delikatny „podgląd" na partial
  (opcja),
- przewijanie ciągłe (velocity-based): prędkość zależy od pozycji pionowej
  ostatniego przeczytanego słowa — 0% przy 20% wysokości viewportu, 100% przy
  80%, liniowo między; słowo jest „niosione" do linii odczytu i przewijanie
  samo się zatrzymuje; pauza podczas dotyku,
- manual override: drag przewija i resetuje alignment do widocznej linii;
  auto-follow zostaje włączone — kolejne wypowiedziane słowo przewija dalej,
- status ASR widoczny: „słucham…" / „pauza" / błąd (np. brak zezwolenia mikrofonu).

## 7. Milestone'y

| # | Zakres | Done gdy |
|---|---|---|
| **M1** | Pipeline: demo Vosk portowane do Kotlin/Compose; PL model w assets; live transcript na ekranie | Mówisz → widzisz słowa na żywo, < 1 s delay |
| **M2** | `GrammarBuilder` + `AlignmentEngine` + testy jednostkowe (fikcyjne transkrypty z błędami/powtórzeniami); A/B słówka vs frazy | `position` stabilny w 10/10 testowych scenariuszy |
| **M3** | Ekrany (editor + prompter), follow/manual, highlight, progress, font/speed | Pełna sesja „mówię → przewija" bez ręcznych poprawek |
| **M4** | Model download w locie (opcja), ustawienia progi, DND/screen-off, bateria, test na 2 telefonach, release | Używalne na co dzień |

Szacowany koszt M1: 1-2 sesje (bindingi proste, model 50 MB do assetsa).

## 8. Ryzyka i mitygacje

| Ryzyko | Wpływ | Mitygacja |
|---|---|---|
| WER PL small model 12-18% | pozycja „skacze" | grammar (słownik z tekstu) tnący błędy; scoring tolerancyjny; hysteresis |
| Duży grammar (tysiące słów) = wolne `new_grm` | jednorazowe opóźnienie startu sesji | pełny grammar raz na start (celowo, bez okienkowania); brak podmian w locie |
| Mówca odchodzi od tekstu (improvizuje) | alignment gubi | tryb manual po N sekund bez progresu; `[unk]` łagodzi |
| Bateria (ASR 24/7) | długi speech = rozładowanie | model small (lekki), pauza po bezczynności, tryb manual bez ASR |
| Jedyne PL model = 1 wybór | sufit jakości | fallback: whisper.cpp `small` jako alternatywny backend (inna ścieżka, M4+) |

## 9. Źródła

- Vosk API: https://github.com/alphacep/vosk-api (C API: `src/vosk_api.h`,
  bindingi: `android/`, `java/`, `kotlin/`, `python/`)
- Demo Android: https://github.com/alphacep/vosk-android-demo
  (`app/src/main/java/org/vosk/demo/VoskActivity.java`)
- Strona Android: https://alphacephei.com/vosk/android
- Lista modeli (PL: `vosk-model-small-pl-0.22`, 50 MB, WER): https://alphacephei.com/vosk/models
- Wymagania demo: `com.alphacephei:vosk-android:0.3.75@aar`, `net.java.dev.jna:jna:5.18.1@aar`,
  minSdk 21 (nasze: 24 ✓)
