# Changelog

## 0.2.0 — 2026-09-13

### Synchronisatie
- Volledige refresh bij appstart en profielwissel.
- Incrementele sync via opgeslagen servercursor tijdens de actieve sessie.
- Automatische sync iedere 30 seconden in foreground.
- Directe sync bij terugkeer naar foreground.
- Directe sync na lokale wijzigingen.
- Synchronisatie en writes worden lokaal geserialiseerd met één mutex.
- `409 revision_conflict` wordt expliciet herkend; de app schrijft niet blind opnieuw.
- Na een conflict wordt direct de nieuwste serverstaat opgehaald.
- Server 1.1.0 conflictmetadata wordt beschikbaar gemaakt in de app-state.
- Serverversie wordt vanuit `/health` weergegeven in Instellingen.

### Platform
- Android lifecycle koppelt automatische sync aan `onResume/onPause`.
- iOS koppelt automatische sync aan SwiftUI `scenePhase`.

### Versie
- Android: 0.2.0 build 10.
- iOS: 0.2.0 build 10.

## 0.1.0

Eerste complete multiplatformbasis met end-to-end versleutelde vaults, activiteiten, profielinstellingen, Excel import/export en API v1-sync.
