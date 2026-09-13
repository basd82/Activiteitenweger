# Roadmap

## Server v2: vóór pairing

1. `devices` losmaken van één `vault_id`.
2. `vault_devices(vault_id, device_id, access_mode, owner, status, revoked_at, revoked_by)` toevoegen.
3. Pairing relay endpoints voor app-gegenereerde tijdelijke codes/links.
4. R en RW cryptografisch als signed capability/grant vastleggen.
5. Behandelaar kan meerdere cliënten/vaults op één installatie beheren.
6. Cliënt kan een behandelaar per vault intrekken.
7. Behandelaar kan eigen toegang tot één cliënt intrekken.
8. Access-event naar cliënt bij intrekken/koppelen/rechtenwijziging.
9. Nieuwe key epoch na revoke voor toekomstige gegevens.
10. Generieke APNs/FCM push: geen cliëntnaam of gezondheidsinhoud in pushpayload.

## App

- ✅ persistente versleutelde lokale cache + outbox voor offline registreren (0.2.1);
- expliciete conflictresolver met keuze tussen lokale en serverversie;
- QR scanner/generator en OS share sheet (WhatsApp/Telegram/Signal/Mail/etc.);
- handmatige invoer/correctie van start- en eindtijd;
- weekgrafieken en dagvergelijking;
- behandelaar-dashboard met meerdere cliënten;
- export/PDF alleen lokaal na decryptie;
- herstelcode/recovery flow;
- advertenties/jaarlijkse ad-free aankoop pas na toestemming en privacyreview.
