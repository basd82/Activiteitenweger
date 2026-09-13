# Synchronisatie

Activiteitenweger gebruikt de versleutelde record-sync van API v1. De server ziet ciphertext en synchronisatiemetadata, maar geen leesbare activiteiten.

## Strategie in app 0.2.0

### Bij appstart

De app doet bewust één volledige refresh vanaf cursor `0`.

Reden: activiteiten worden in 0.2.0 nog in geheugen gehouden en niet als complete persistente lokale cache opgeslagen. Alleen vanaf een eerder opgeslagen cursor synchroniseren na een procesherstart zou daardoor oudere activiteiten kunnen missen.

### Tijdens een actieve app-sessie

Na de volledige refresh bewaart de app de laatst verwerkte `nextCursor` in de `VaultSession`.

Daarna gebruikt iedere gewone sync:

```text
GET /api/v1/sync?since=<laatste cursor>&limit=500
```

De response wordt in de huidige lokale lijst gemerged:

- UPSERT/actueel record vervangt dezelfde `recordId`;
- tombstone verwijdert dezelfde `recordId` lokaal;
- profielinstellingen worden apart verwerkt;
- `nextCursor` wordt pas na succesvolle verwerking opgeslagen.

## Wanneer wordt gesynchroniseerd?

De app synchroniseert:

1. volledig bij appstart;
2. volledig bij profielwissel;
3. direct na een succesvolle lokale wijziging;
4. direct wanneer de app weer foreground wordt;
5. iedere 30 seconden zolang de app foreground is;
6. handmatig via **Nu synchroniseren**.

Android gebruikt `onResume/onPause`; iOS gebruikt SwiftUI `scenePhase`.

## Gelijktijdige acties

Een lokale mutex serialiseert server-sync en schrijfacties. Daardoor kan de periodieke sync niet midden in een lokale wijziging dezelfde UI-state vervangen.

## Revision-conflicten

Voor een bestaand record moet de volgende revision exact de huidige serverrevision + 1 zijn.

Voorbeeld:

```text
server revision 4
apparaat A schrijft revision 5 -> geaccepteerd
apparaat B schrijft ook revision 5 -> 409 revision_conflict
```

Bij `409 revision_conflict`:

- de app probeert **niet** automatisch opnieuw met `expectedRevision`;
- de lokale wijziging wordt niet als succesvol gemarkeerd;
- de conflictmetadata uit server 1.1.0 wordt bewaard in de UI-state;
- de app haalt direct de nieuwste serverstaat op;
- de gebruiker krijgt expliciet gemeld dat de lokale wijziging niet is opgeslagen.

Dit voorkomt onzichtbaar overschrijven.

## Verwijderen

Verwijderen is een tombstone met een hogere revision. Een apparaat met een oude revision kan een verwijdering daardoor niet ongemerkt ongedaan maken.

## Profielinstellingen

Profielnaam, categorieën, punten en standaardactiviteiten zijn samen één end-to-end versleuteld profielinstellingen-record. Daardoor kan gelijktijdig wijzigen van twee verschillende instellingen op twee apparaten alsnog één revision-conflict veroorzaken.

## Offline beperking in 0.2.0

App 0.2.0 heeft nog geen persistente lokale activiteitcache met outbox. Een wijziging wordt daarom nog direct naar de server geschreven.

Een volgende offline-fase krijgt:

- persistente versleutelde lokale cache;
- pending/outbox-status per mutatie;
- retry na herstel van netwerk;
- expliciete conflictresolutie waarbij de gebruiker lokale en serverversie kan vergelijken.

Tot die tijd kiest de app bij onzekerheid voor gegevensveiligheid: niet blind overschrijven en na processtart altijd eerst volledig synchroniseren.
