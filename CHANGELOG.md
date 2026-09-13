# Changelog

## 0.2.1 — 2026-09-13

### Offline synchronisatie
- Persistente versleutelde lokale recordcache toegevoegd.
- Durable outbox voor nieuwe, gewijzigde, gestopte en verwijderde activiteiten.
- Activiteiten kunnen worden geregistreerd terwijl het apparaat offline is.
- Pending records tonen **Wacht op synchronisatie** in de UI.
- Exact voorbereide encrypted requests worden bij retry hergebruikt.
- Een timeout na servercommit kan bij de volgende pull veilig als eigen write worden herkend.
- Meerdere offline wijzigingen aan hetzelfde record worden samengevoegd.
- Lokale nieuwe records die vóór de eerste upload worden verwijderd, verdwijnen zonder onnodige server-tombstone.
- Pending- en conflict-aantallen zijn zichtbaar bij Instellingen.
- Offline netwerkfouten tonen geen ruwe NSURL/Ktor-exception meer.

### Versie
- Android: 0.2.1 build 11.
- iOS: 0.2.1 build 11.


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
