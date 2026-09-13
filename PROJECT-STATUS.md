# Status van deze build

**App-versie:** 0.2.1 (build 11)

**Werkende basis volgens API v1 / server 1.1.0:** vault-create, signing, encrypted record create/update/delete, persistente versleutelde lokale cache, offline outbox, incrementele cursor-sync, tombstones, revision-conflictdetectie, complete vault delete, adaptive Compose UI en secure lokale key storage.

Synchronisatiegedrag:
- lokale activiteiten worden eerst versleuteld op het apparaat opgeslagen;
- nieuwe activiteiten, stoppen, wijzigen en verwijderen werken zonder internet;
- pending mutaties bewaren exact nonce/ciphertext/signature zodat een retry identiek is;
- pending wijzigingen worden automatisch verstuurd zodra sync weer lukt;
- na een onzekere POST kan de volgende pull een eigen reeds-gecommitte write herkennen aan revision + writerDeviceId + recordSignature;
- incrementele sync via de duurzaam opgeslagen servercursor;
- direct na eigen wijzigingen;
- direct bij terugkeer naar foreground;
- iedere 30 seconden zolang de app foreground is;
- lokale schrijfacties en sync worden geserialiseerd;
- bij `409 revision_conflict` wordt niet blind opnieuw geschreven; de lokale mutatie blijft als conflict bewaard.

De lokale activity-cache bevat server-compatible versleutelde records; leesbare activiteiten worden niet als platte JSON-cache opgeslagen. De VaultKey blijft in secure storage.

**Nog niet aanwezig:** interactieve conflictresolver met keuze tussen lokale en serverversie.

**Niet mogelijk met huidige server v1:** een tweede device/behandelaar registreren via pairing. Hiervoor is de geplande multi-client/pairing-servermigratie nodig.
