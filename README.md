# Prompter

Głosowy teleprompter z auto-przewijaniem (Vosk, offline, polski).
Szczegóły projektu: [PROJEKT.md](PROJEKT.md).

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

**Instalacja na urządzeniu: ręcznie, przez użytkownika** (kopia APK na telefon i
instalacja). Nie pchać automatycznie przez `adb install` / MTP z maszyny
deweloperskiej — device zwykle w ogóle nie jest podłączony przez adb.
