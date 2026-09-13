# Huidig API-contract v1

Base URL: `https://app.dikkenberg.net/api/v1`

Routes die deze app gebruikt:

- `GET /health`
- `POST /vaults`
- `GET /me`
- `GET /devices`
- `POST /records`
- `GET /sync?since=<cursor>&limit=<n>`
- `DELETE /vaults/{uuid}`

Alle endpoints behalve health/create-vault gebruiken Ed25519 request signing. Zie `SECURITY.md`.

## Activiteit-payload vóór encryptie

```json
{
  "schemaVersion": 1,
  "type": "activity",
  "startedAt": "2026-09-12T08:00:00Z",
  "endedAt": "2026-09-12T08:18:00Z",
  "description": "Douchen",
  "category": "zwaar"
}
```

Duur en punten zijn afgeleide waarden en worden niet als leidende data opgeslagen.


## Profielinstellingen-payload vóór encryptie

Profielinstellingen gebruiken de bestaande generieke `POST /records` en `GET /sync` laag. Er is geen aparte plaintext instellingen-endpoint.

Het instellingenrecord gebruikt als `recordId` de UUID van de vault zelf. Omdat vault-id's UUID's en uniek zijn, voldoet dit aan het bestaande recordcontract. De inhoud wordt met dezelfde VaultKey en XChaCha20-Poly1305 versleuteld als activiteiten.

Voorbeeld vóór encryptie:

```json
{
  "schemaVersion": 1,
  "type": "profile_settings",
  "label": "Mijn Activiteitenweger",
  "categories": [
    {"id":"ontspanning","label":"Ontspanning","pointsPer30Minutes":-1.0},
    {"id":"licht","label":"Licht","pointsPer30Minutes":1.0}
  ],
  "activityPresets": [
    {"id":"preset-...","label":"Douchen","categoryId":"licht"}
  ]
}
```

De lokale `settingsRevision` volgt de serverrevision van dit record. Wijzigingen aan profielnaam, categorieën, punten of standaardactiviteiten schrijven steeds een nieuwe revision. Bij sync wordt dit record apart van activiteitrecords verwerkt.


## Revision-conflict

Een bestaand record mag alleen met exact `huidige revision + 1` worden geschreven.

Server 1.1.0 retourneert bij een stale write:

```json
{
  "error": "revision_conflict",
  "recordId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "currentRevision": 5,
  "expectedRevision": 6,
  "currentDeleted": false,
  "currentUpdatedAt": "2026-09-13T14:00:00.000000Z"
}
```

De app gebruikt `expectedRevision` nadrukkelijk niet voor een automatische retry. Eerst wordt `GET /sync` uitgevoerd vanaf de laatst verwerkte cursor, zodat een wijziging van een ander apparaat niet stilletjes wordt overschreven.

## Sync-cursor

`nextCursor` is de laatst verwerkte server-`event_id`. Binnen een actieve app-sessie wordt deze cursor incrementeel gebruikt.

Omdat app 0.2.0 nog geen persistente lokale activiteitcache heeft, begint de eerste sync na een processtart bewust vanaf `0`. Zie `SYNC.md`.
