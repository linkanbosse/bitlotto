# BitLotto ₿

Android-app med **egen bitcoin-plånbok** och **solo-miner** i samma app.

- **Plånbok:** BDK 3.0 (Bitcoin Dev Kit), BIP84 (bc1…/tb1…-adresser), 12-ords återställningsfras, ta emot med QR, skicka med avgiftsval, transaktionshistorik. Synkar via mempool.space (Esplora).
- **Miner:** SHA-256d i ren Java med midstate-optimering, Stratum v1 mot en solo-pool (CKPool Solo / Public Pool). Hittar du ett block betalar poolen hela belöningen direkt till din adress.
- **Skydd:** minar som standard bara vid laddning, pausar vid hög batteritemperatur och vid systemets värmevarning, lägsta trådprioritet.

> Mining på mobil är ett lotteri. Förväntat värde är i storleksordningen en miljondels krona per timme. Chansen att hitta ett block är astronomiskt liten men inte noll.

## Bygga från mobilen (GitHub Actions)

1. Ladda upp alla filer till ett GitHub-repo (helst **privat**, se Säkerhet).
2. Varje push till `main` bygger appen automatiskt (fliken **Actions**). Det tar 3–6 min.
3. Hämta APK:n under **Releases**, där senaste bygget heter `BitLotto build N`. Du kan också hämta den som artefakt under Actions-körningen.
4. Öppna APK:n på telefonen och tillåt "Installera okända appar" för webbläsaren eller Filer.

Uppdateringar installeras ovanpå den gamla versionen, och plånboken finns kvar. Alla byggen signeras med samma nyckel (`app/bitlotto.keystore`).

Om bygget fallerar: öppna den röda körningen under Actions, välj steget *Build debug APK* och kopiera felet till Claude.

## Kom igång i appen

1. **Skapa plånbok** och skriv ner de 12 orden på papper.
2. Plånboken startar på **Testnet4**. Prova att ta emot och skicka med gratis testmynt från en testnet4-faucet.
3. **Miner:** under Inställningar anger du en **mainnet**-adress för utbetalning. Antingen byter du plånboken till Mainnet och trycker *Använd plånboken*, eller så klistrar du in en egen adress.
4. Sätt telefonen på laddning och tryck **Starta mining**.

## Filer

| Fil | Innehåll |
|---|---|
| `mining/Sha256.java`, `HeaderHasher.java` | SHA-256d med midstate (verifierad mot genesis-blocket) |
| `mining/StratumJob.java` | Bygger coinbase, merkle-rot och 80-byte header |
| `mining/MiningEngine.java` | Flertrådad miner, andels-/blockkontroll |
| `mining/StratumClient.java` | Stratum v1 över TCP med återanslutning |
| `mining/MiningService.kt` | Förgrundstjänst, wakelock, värme- och laddningsvakt |
| `wallet/WalletRepository.kt` | All BDK-logik (skapa/ladda, synk, skicka) |
| `wallet/SeedVault.kt` | Återställningsfrasen krypterad med Android Keystore (AES-256-GCM) |
| `ui/*` | Jetpack Compose-gränssnitt |

## Säkerhet

- Återställningsfrasen krypteras med en nyckel i telefonens Keystore och tas inte med i backup.
- Signeringsnyckeln för APK:n ligger i repot. Det gör uppdateringar enkla, men den som har repot kan signera en app som ser ut som en uppdatering. Håll därför repot **privat**.
- Appen är egenbyggd och inte säkerhetsgranskad. Förvara bara små belopp på Mainnet.
- Google Play tillåter inte mining på enheten. Appen är avsedd för egen installation (sideload).
