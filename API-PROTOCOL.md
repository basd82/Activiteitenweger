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
