# Activiteitenweger

Kotlin Multiplatform / Compose Multiplatform app voor iPhone, iPad, Android-telefoon, Android-tablet en Chromebook.

## Repositories

- **App:** [basd82/Activiteitenweger](https://github.com/basd82/Activiteitenweger)
- **API:** [basd82/Activiteitenweger-api](https://github.com/basd82/Activiteitenweger-api)

## Wat deze eerste complete projectbasis al doet

- maakt een nieuwe end-to-end versleutelde vault via `https://app.dikkenberg.net/api/v1`;
- genereert lokaal Ed25519 signing keys, X25519 keymateriaal en een 256-bit VaultKey;
- bewaart geheimen in platform-secure storage via KVault (Android encrypted storage / iOS Keychain);
- ondertekent API-verzoeken exact volgens `AW-REQUEST-V1`;
- versleutelt activiteiten lokaal met XChaCha20-Poly1305 voordat ze naar de server gaan;
- ondertekent records volgens `AW-RECORD-V1`;
- ondersteunt start activiteit / klaar met activiteit;
- ondersteunt handmatig activiteiten invoeren met start- en einddatum/tijd;
- ondersteunt afgeronde activiteiten wijzigen en verwijderen;
- ondersteunt profielnamen lokaal wijzigen;
- categorieën en punten per 30 minuten zijn per profiel instelbaar; categorieën kunnen worden toegevoegd, gewijzigd en verwijderd;
- standaardactiviteiten kunnen per profiel worden toegevoegd, gewijzigd en verwijderd en via een keuzelijst worden gebruikt bij registratie;
- importeert en exporteert het Activiteitenweger-dagschema als Excel (.xlsx), inclusief oudere dagbladen zonder jaartal;
- start- en eindtijd worden automatisch vastgelegd;
- rekent punten proportioneel uit: Ontspanning -1, Licht +1, Gemiddeld +2, Zwaar +3 per 30 minuten;
- synchroniseert records en tombstones met de bestaande server-API;
- bewaart een persistente lokale cache met alleen versleutelde recorddata;
- gebruikt een offline outbox: activiteiten starten, stoppen, wijzigen en verwijderen blijft mogelijk zonder internet;
- gebruikt na de eerste cache-opbouw de sync-cursor ook over app-herstarts heen incrementeel;
- synchroniseert direct na eigen wijzigingen, bij terugkeer naar de app en iedere 30 seconden zolang de app actief is;
- lokale Start/Stop/Wijzig-acties wachten nooit op een netwerkrequest; een hangende automatische sync wordt daarvoor afgebroken;
- blokkeert gelijktijdige sync/schrijfacties lokaal en overschrijft een serverwijziging nooit blind bij `409 revision_conflict`;
- ondersteunt meerdere lokale vault-profielen in één app-installatie;
- verwijdert een eigen vault volledig via de server-API;
- adaptive UI voor telefoon, tablet en Chromebook.

## Nog bewust niet actief

Categorieën, punten, profielnaam en standaardactiviteiten worden als één end-to-end versleuteld profielinstellingen-record via dezelfde vault-sync gesynchroniseerd. De server ziet alleen ciphertext.

Pairing via QR/koppelcode, R/RW grants, self-revoke van een behandelaar, access-events en key rotation zijn in de UI/datamodellen voorbereid maar kunnen nog niet functioneel zijn totdat de server de pairing/device-grant endpoints en de many-to-many `vault_devices` migratie heeft. De app doet hier dus niet alsof het al werkt.

## Versies (13 september 2026)

App-versie: **0.2.2 (build 12)**  
Verwachte API-major: **v1**; getest met server **1.1.0**

- Kotlin 2.4.20
- Compose Multiplatform 1.11.1
- Android Gradle Plugin 9.1.1
- Gradle 9.7.1
- Android compile SDK 36
- Android target SDK 36
- Ktor 3.5.2
- kotlinx.coroutines 1.11.0
- kotlinx.serialization 1.11.0
- kotlinx-datetime 0.8.0
- cryptography-kotlin 0.6.0
- KVault 1.12.0
- Kexcel 0.1.1
- FileKit 0.14.2

## Eerste keer openen

De ZIP bevat om bestandsgrootte/licentieredenen geen `gradle-wrapper.jar`. Op macOS/Linux:

```bash
./bootstrap-gradle-wrapper.sh
./gradlew projects
```

Open daarna de project-root in IntelliJ IDEA 2026.1.2+ of een actuele Android Studio-versie en laat Gradle synchroniseren.

Je kunt vóór het openen ook een compilecheck doen:

```bash
./verify-project.sh
```

Op macOS compileert die zowel Android als het passende iOS-simulatorframework; op Linux alleen Android.

### Android

Run configuration/module: `androidApp`.

De app declareert touchscreen niet als verplicht en is resizable, zodat dezelfde Android-build ook geschikt is voor ondersteunde Chromebooks.

### iOS

1. Vul indien nodig `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` in.
2. Open `iosApp/iosApp.xcodeproj` in Xcode.
3. Kies een iPhone/iPad simulator of device en Run.

Xcode roept automatisch `:shared:embedAndSignAppleFrameworkForXcode` aan.

## Belangrijke beveiligingskeuzes

- De API krijgt nooit plaintext activiteiten of de VaultKey.
- De private Ed25519 key, private X25519 key en VaultKey staan alleen in secure storage op het device.
- Record-AAD is de `recordId`, gelijk aan de huidige PHP smoke-testclient.
- Serverrecords zijn ciphertext + nonce + signature + minimale synchronisatiemetadata.
- `allowBackup=false` staat op Android om ongewenste OS-backup van appdata te voorkomen.

Zie ook `SECURITY.md`, `SYNC.md` en `ROADMAP.md`.

## Juridisch/medisch

De app is een registratie- en inzichtshulpmiddel. Scores zijn geen medische beoordeling en de app vervangt geen advies, diagnose, behandeling of begeleiding door een gekwalificeerde professional.


## Auteursrecht en licentie

Copyright © 2026 Bas van den Dikkenberg.

Activiteitenweger is vrije/open-sourcesoftware en wordt uitgebracht onder de **GNU General Public License versie 3.0 (GPL-3.0-only)**.

Je mag de software gebruiken, bestuderen, wijzigen en verspreiden onder de voorwaarden van GPLv3. Er wordt geen garantie gegeven, voor zover wettelijk toegestaan.

Zie [LICENSE](LICENSE) voor de volledige licentietekst.
