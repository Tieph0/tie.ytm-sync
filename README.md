# YTM Sync

Android-App, die **Likes und Playlists** zwischen deinem YouTube-Music-Hauptkanal und einem **Brand-Kanal** (z. B. `Tieph0`) stündlich in beide Richtungen abgleicht.

## Funktionsweise

- **Likes:** Was auf einer Seite geliked ist, wird auf der anderen auch geliked.
- **Playlists:** Eigene Playlists werden über den Titel zugeordnet. Fehlende Playlists werden privat angelegt, fehlende Songs ergänzt.
- **Es wird nur hinzugefügt, nie gelöscht.** Wenn du etwas entfernst, musst du es auf beiden Kanälen entfernen.
- Pro Lauf höchstens 250 Schreibaktionen. Ein großer erster Abgleich verteilt sich deshalb auf mehrere Läufe.

## Akku

- Kein Dauer-Service. Der Sync läuft über Android-WorkManager: ungefähr einmal pro Stunde, in einem 15-Minuten-Fenster gebündelt mit anderen Jobs.
- Er startet nur mit Netz (optional nur im WLAN) und wenn der Akku nicht fast leer ist.
- WorkManager registriert den Job nach einem Neustart selbst neu. Autostart ist also automatisch dabei.

## Installation

1. Die neueste APK unter **Releases** herunterladen (bei jedem Push baut GitHub Actions eine neue).
2. Installieren, öffnen, **Mit Google anmelden**.
3. Den Brand-Kanal auswählen. Der erste Sync startet direkt.

Falls Google den Login in der App blockiert: **Cookie manuell einfügen** (die Anleitung steht im Dialog).

## Hinweise

- Die App nutzt die inoffizielle interne YouTube-Music-API (wie ytmusicapi). Wenn YouTube dort etwas ändert, kann der Sync brechen.
- Der Login-Cookie liegt nur app-privat auf dem Gerät.
- `app/release.jks` ist ein Signierschlüssel nur für dieses private Repo. Er sorgt dafür, dass Updates sich über die alte Version installieren lassen. **Das Repo deshalb nicht öffentlich machen.**
