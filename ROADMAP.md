# Roadmap

## Huidig checkpoint

**App 0.2.2 (build 12)** vormt de stabiele basis voor de volgende fase.

Werkend op dit checkpoint:
- end-to-end versleutelde activiteiten;
- persistente versleutelde lokale cache;
- offline outbox;
- starten, stoppen, wijzigen en verwijderen zonder internet;
- incrementele cursor-sync;
- automatische foreground-sync;
- revision-conflictdetectie zonder blind overschrijven;
- Android- en iOS-build via CI.

**Volgende ontwikkelstap:** pairing / tweede apparaat of behandelaar met per-vault **R/RW-rechten**.

## Server v2: pairing en multi-device

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
- ✅ lokale Start/Stop/Wijzig-acties blokkeren niet op netwerk (0.2.2);
- pairing-flow voor tweede apparaat/behandelaar;
- keuze bij koppelen tussen R en RW;
- QR scanner/generator en handmatige koppelcode;
- device-/toegangsoverzicht per vault;
- toegang intrekken;
- expliciete conflictresolver met keuze tussen lokale en serverversie;
- OS share sheet (WhatsApp/Telegram/Signal/Mail/etc.);
- handmatige invoer/correctie van start- en eindtijd;
- weekgrafieken en dagvergelijking;
- behandelaar-dashboard met meerdere cliënten;
- export/PDF alleen lokaal na decryptie;
- herstelcode/recovery flow;
- advertenties/jaarlijkse ad-free aankoop pas na toestemming en privacyreview.
