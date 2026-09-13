# Status van deze build

**App-versie:** 0.2.0 (build 10)

**Werkende basis volgens API v1 / server 1.1.0:** vault-create, signing, encrypted record create/update/delete, volledige initiële sync, incrementele cursor-sync, tombstones, revision-conflictdetectie, complete vault delete, adaptive Compose UI en secure lokale key storage.

Synchronisatiegedrag:
- volledige refresh bij appstart en profielwissel;
- daarna incrementeel via de laatst verwerkte servercursor;
- direct na eigen wijzigingen;
- direct bij terugkeer naar foreground;
- iedere 30 seconden zolang de app foreground is;
- lokale schrijfacties en sync worden geserialiseerd;
- bij `409 revision_conflict` wordt niet opnieuw/blind geschreven; de nieuwste serverstaat wordt eerst opgehaald.

**Nog niet aanwezig:** persistente lokale activiteitcache/outbox voor volledig offline wijzigen. Daarom doet de app na een procesherstart bewust eerst een volledige refresh vanaf cursor 0.

**Niet mogelijk met huidige server v1:** een tweede device/behandelaar registreren via pairing. Hiervoor is de geplande multi-client/pairing-servermigratie nodig.
