# Synchronisatie

Activiteitenweger gebruikt de versleutelde record-sync van API v1. De server ziet ciphertext en synchronisatiemetadata, maar geen leesbare activiteiten.

## Strategie in app 0.2.1

De app heeft een persistente lokale versleutelde recordcache en outbox. Daardoor kan de servercursor ook over app-herstarts heen veilig incrementeel worden gebruikt.

Bij een bestaande installatie zonder lokale cache begint de eerste succesvolle sync vanaf cursor `0`; daarna is de lokaal opgeslagen cursor leidend.

Iedere gewone sync gebruikt:

```text
GET /api/v1/sync?since=<laatste cursor>&limit=500
```

De response wordt in de versleutelde lokale cache gemerged:

- UPSERT/actueel record vervangt dezelfde `recordId`;
- tombstones blijven lokaal als serverstaat bewaard zodat oude data niet kan herleven;
- pending lokale mutaties worden bovenop de servercache weergegeven;
- profielinstellingen worden als hetzelfde type versleuteld record verwerkt;
- `nextCursor` wordt pas na succesvolle lokale opslag van de pagina vooruitgezet.

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

## Nog open

De persistente versleutelde cache en outbox zijn actief vanaf app 0.2.1.

Wat nog volgt is de interactieve conflictresolver waarbij de gebruiker bij een echte multi-device conflict kan kiezen tussen de lokale wijziging en de serverversie. Tot die tijd blijft een conflicterende pending mutatie lokaal bewaard en wordt deze niet automatisch overschreven.


## Offline outbox

Een schrijfhandeling gaat eerst naar de lokale outbox. Hiervoor wordt direct de definitieve serverrequest voorbereid en versleuteld:

- `recordId`;
- `baseRevision`;
- `revision`;
- `keyEpoch`;
- `deleted`;
- ciphertext + nonce;
- `recordSignature`;
- lokaal wijzigingstijdstip.

Dezelfde pending wijziging wordt bij retry niet opnieuw versleuteld. Daardoor kan na een netwerktimeout worden vastgesteld of de server de oorspronkelijke write toch al heeft gecommit.

Meerdere lokale wijzigingen aan hetzelfde nog-pending record worden samengevoegd tot één mutatie met dezelfde `baseRevision + 1`. Een nieuw offline gestart en daarna gestopt item kan dus als één revision 1 naar de server.

Als een lokaal nieuw record vóór de eerste sync weer wordt verwijderd, wordt de pending create helemaal uit de outbox verwijderd; er hoeft dan geen tombstone naar de server.

## Herstel na netwerkuitval

Zodra een foreground-sync weer slaagt:

1. eerst remote wijzigingen ophalen;
2. pending mutaties zonder conflict versturen;
3. daarna opnieuw pullen vanaf de nog niet vooruitgeschoven cursor;
4. pas verwerkte syncpagina's duurzaam opslaan.

Een HTTP-timeout na een succesvolle servercommit blijft veilig: bij de volgende pull geldt een remote record als bevestiging van onze pending write als revision, `writerDeviceId` en `recordSignature` exact overeenkomen.
