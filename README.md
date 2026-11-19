# YTM Sync

[![Build APK](https://github.com/Tieph0/tie.ytm-sync/actions/workflows/build.yml/badge.svg)](https://github.com/Tieph0/tie.ytm-sync/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Tieph0/tie.ytm-sync?label=APK)](https://github.com/Tieph0/tie.ytm-sync/releases/latest)

Android-App, die **Likes und Playlists** zwischen einem YouTube-Music-Hauptkanal und einem **Brand-Kanal** desselben Google-Kontos stündlich in beide Richtungen abgleicht.

Sinnvoll ist das, wenn deine Musik auf einem Brand-Kanal liegt, eine Drittanbieter-App (z. B. Obsidian Music) beim Login aber nur den Hauptkanal nimmt.

> ⚠️ Inoffizielles Projekt, steht in keiner Verbindung zu Google oder YouTube. Die App nutzt die interne YouTube-Music-API (wie [ytmusicapi](https://github.com/sigma67/ytmusicapi)). Ändert YouTube dort etwas, kann der Sync brechen. Nutzung auf eigenes Risiko.

## Funktionsweise

- **Likes:** Was auf einem Kanal geliked ist, wird auf dem anderen auch geliked.
- **Playlists:** Eigene Playlists werden über den Titel zugeordnet. Fehlende Playlists werden privat angelegt, fehlende Songs ergänzt.
- **Es wird nur hinzugefügt, nie gelöscht.** Wenn du etwas entfernst, musst du es auf beiden Kanälen entfernen.
- Pro Lauf höchstens 250 Schreibaktionen. Ein großer erster Abgleich verteilt sich deshalb auf mehrere Läufe.
- **Sicherheitscheck:** Zeigen Haupt- und Brand-Seite auf denselben Kanal, bricht der Sync ab, statt Unsinn zu schreiben.

## Akku

- Kein Dauer-Service. Der Sync läuft über Android-WorkManager: ungefähr einmal pro Stunde, in einem 15-Minuten-Fenster gebündelt mit anderen Jobs.
- Er startet nur mit Netz (optional nur im WLAN) und wenn der Akku nicht fast leer ist.
- WorkManager registriert den Job nach einem Neustart selbst neu. Autostart ist also automatisch dabei.
- Benachrichtigungen gibt es nur, wenn der Login abläuft.

## Installation

1. Die neueste APK unter [**Releases**](https://github.com/Tieph0/tie.ytm-sync/releases/latest) herunterladen.
2. Installieren. Android fragt, ob die Installation aus unbekannten Quellen erlaubt werden soll.
3. App öffnen → **Mit Google anmelden** (mit dem Konto, unter dem beide Kanäle liegen).
4. Den Brand-Kanal auswählen. Der erste Sync startet direkt.

Falls Google den Login in der App blockiert: **Cookie manuell einfügen** (die Anleitung steht im Dialog). Wird der Brand-Kanal nicht automatisch gefunden: Auf [myaccount.google.com/brandaccounts](https://myaccount.google.com/brandaccounts) den Kanal öffnen und die lange Zahl aus der URL als ID eintragen.

Voraussetzung ist Android 8.0 oder neuer.

## Datenschutz

- Der Login-Cookie liegt nur app-privat auf dem Gerät und wird ausschließlich an `music.youtube.com` gesendet.
- Es gibt keinen eigenen Server, kein Tracking und keine Analytics.

## Selbst bauen

```bash
git clone https://github.com/Tieph0/tie.ytm-sync && cd tie.ytm-sync
gradle assembleRelease        # benötigt JDK 17, Android SDK 35, Gradle 8.11+
```

Ohne Signierschlüssel wird die APK mit dem Debug-Key signiert.

### CI und Signierung

Jeder Push auf `main` baut über GitHub Actions eine APK und veröffentlicht sie als Release `build-<Nummer>`.

Signiert wird mit einem Schlüssel aus den Repo-Secrets:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_B64` | Keystore (`.jks`) als Base64, Alias `ytmsync` |
| `KEYSTORE_PASSWORD` | Passwort für Keystore und Key |

Ein fester Schlüssel sorgt dafür, dass sich Updates über die alte Version installieren lassen. In Forks fehlen die Secrets. Dort wird mit einem temporären Debug-Key signiert, und Updates gehen nur nach einer Deinstallation.

## Aufbau

| Datei | Aufgabe |
|---|---|
| `YtmClient.kt` | InnerTube-Client (Cookie/SAPISIDHASH-Auth, Paging, Likes, Playlists) |
| `SyncEngine.kt` | Zwei-Wege-Abgleich, nur hinzufügen, Schreiblimit pro Lauf |
| `SyncWorker.kt` / `Scheduler.kt` | Stündlicher WorkManager-Job und manueller Sync |
| `LoginActivity.kt` | Google-Login per WebView |
| `MainActivity.kt` | Compose-Oberfläche |
