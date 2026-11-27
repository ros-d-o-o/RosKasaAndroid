# Worldline Tap On Mobile — Testni scenariji za `WPI_SVC_LAST_TRANSACTION`

Ta dokument opisuje vse 3 scenarije okrevanja Worldline (SixTap) plačil in natančen postopek, kako jih kadarkoli ponovno simulirati na pravi Android napravi z nameščeno aplikacijo **Worldline Tap On Mobile**.

---

## Pregled scenarijev in pravil

| Scenarij | Opis dogodka | Pričakovano delovanje RosKasa |
| :--- | :--- | :--- |
| **1.1** | Transakcija na Worldline **uspe**, RosKasa pa prejme **negativen rezultat** (`RESULT_CANCELED`) brez takojšnjega `LAST_TRANSACTION`. | `RecoveryPayment` se sinhrono (`commit()`) shrani v `SharedPreferences` (`SixTapRecoveryPrefs`). Ob **naslednjem poskusu Worldline plačila**: 1. klik opozorilo (`ESKALACIJA 1`), 2. klik (ali tipka `Tap On`) samodejno sproži `WPI_SVC_LAST_TRANSACTION` (`ESKALACIJA 2`), poknjiži `setSlipEma` + `setRacun`, izpiše račun in pobriše vse registre/flage. |
| **1.2** | Transakcija na Worldline **uspe**, RosKasa pa prejme **negativen rezultat** (`RESULT_CANCELED`). | RosKasa takoj **izven `onSixTapResult` callbacka** (prek `Handler(Looper.getMainLooper()).post(...)`, brez rekurzije/zaciklanja) sproži `WPI_SVC_LAST_TRANSACTION`, prejme potrditev, poknjiži `setSlipEma` + `setRacun`, izpiše račun in pobriše vse registre/flage. |
| **2** | Transakcija na Worldline **uspe** (ali je prekinjena), RosKasa pa se **sesuje / pade ven** (`killProcess`). | Ker je `PendingPayment` sinhrono (`commit()`) shranjen pred zagonom Worldline, ob **ponovnem zagonu in prijavi** `MainActivity.tapOnRecoverExecute()` najde shranjene flage, preveri odprti račun na strežniku, sproži `WPI_SVC_LAST_TRANSACTION`, ob uspehu odpre/poknjiži račun (`setSlipEma` + `setRacun`), ga izpiše ter ob uspehu ali neuspehu **pobriše vse registre/flage**. |
| **Pravilo 3** | Po vsaki operaciji `LAST_TRANSACTION` (uspeh, neuspeh ali preklic). | `PaymentRecoveryManager.clearAllRecoveryData(context)` obvezno pobriše `PendingPayment`, `RecoveryPayment`, `ConfirmedPOSTransaction` in ponastavi `sixtapintransaction = false`. |

---

## Ključne datoteke v izvorni kodi

- `app/src/main/java/si/ros/RosKasa/payment/SixTapPaymentService.java` — `prepareAndCheckPayment` (eskalacija 1/2/3), `buildPaymentIntent` (shranjevanje `PendingPayment` pred `OP_PAYMENT`), `parseResponseIntent`.
- `app/src/main/java/si/ros/RosKasa/payment/PaymentRecoveryManager.java` — sinhroni (`commit()`) zapis in brisanje `SixTapRecoveryPrefs`.
- `app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java` — `handleSixTapResult` in `izvediSixTapLastTransaction` (plačilo iz zavihka **Plačila**).
- `app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java` — `handleSixTapResult` in `izvediSixTapLastTransaction` (hitro kartično plačilo iz zavihka **Naročila**).
- `app/src/main/java/si/ros/RosKasa/MainActivity.java` — `tapOnRecoverExecute`, `handleStartupSixTapResult` in `bookConfirmedPOSTransaction` (obnova ob ponovnem zagonu po sesutju).
- `app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java` — `setSlipEma` (`STRM_ID` fallback + `STTYPE` skrajšan na max 19 znakov skladno z Delphi `Copy(pOperacija, 0, 19)`).

---

## Kako ponovno vklopiti začasno simulacijo

Simulacija prestreže **samo** povratek iz `OP_PAYMENT` (`handleSixTapResult`), **nikoli** pa ne prestreže `OP_LAST_TRANSACTION`, zato se na pravi napravi izvede resnična Worldline transakcija in nato resnični Worldline `WPI_SVC_LAST_TRANSACTION`.

### 1. Dodaj konstanto v `SixTapPaymentService.java`

Pod `OP_CANCEL_PAYMENT`:

```java
// === TODO REMOVE SIMULATION AFTER WORLDLINE TESTS ===
// 0  = izklopljeno (normalno delovanje)
// 11 = Scenarij 1.1: Worldline uspe, RosKasa dobi RESULT_CANCELED (brez takojšnjega LAST_TRANSACTION)
// 12 = Scenarij 1.2: Worldline uspe, RosKasa dobi RESULT_CANCELED -> takojšnji LAST_TRANSACTION izven callbacka
// 2  = Scenarij 2: Povratek iz Worldline -> umetno sesutje RosKasa (killProcess) -> obnova ob zagonu
public static final int SIMULATE_WORLDLINE_SCENARIO = 11;
// === END TODO REMOVE SIMULATION ===
```

### 2. Dodaj prestrezanje v `handleSixTapResult(...)` v OBH fragmentih (`PlacilaFragment.java` IN `NarocilaFragment.java`)

Na začetek metode `handleSixTapResult(...)`, takoj za `final int racunId = ...;`:

```java
// === TODO REMOVE SIMULATION AFTER WORLDLINE TESTS ===
boolean simForceNegative = false;
boolean simForceAutoLast = false;
boolean simSuppressAutoLast = false;
if (SixTapPaymentService.SIMULATE_WORLDLINE_SCENARIO == 2) {
    Globals.getInstance().vpisiKronologijo("SIMULACIJA SCENARIJ 2: Povratek iz Worldline R:" + racunId + " resultCode:" + resultCode + " -> umetno sesujem RosKasa (killProcess)!");
    Globals.getInstance().posljiOfflineKronologijo();
    Toast.makeText(requireContext(), "SIMULACIJA 2: Simuliram sesutje RosKasa (PendingPayment ostane shranjen)!", Toast.LENGTH_SHORT).show();
    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }, 600);
    return;
}
if (SixTapPaymentService.SIMULATE_WORLDLINE_SCENARIO != 0 && resultCode == android.app.Activity.RESULT_OK && data != null) {
    SixTapResponse simCheck = SixTapPaymentService.parseResponseIntent(data);
    if (simCheck.isSuccess()) {
        if (SixTapPaymentService.SIMULATE_WORLDLINE_SCENARIO == 11) {
            Globals.getInstance().vpisiKronologijo("SIMULACIJA SCENARIJ 1.1: Worldline USPEH R:" + racunId + " M_REF:" + simCheck.getPaymentSolutionReference() + " -> umetno pretvarjam v RESULT_CANCELED");
            simForceNegative = true;
            simSuppressAutoLast = true;
            resultCode = android.app.Activity.RESULT_CANCELED;
            data = null;
        } else if (SixTapPaymentService.SIMULATE_WORLDLINE_SCENARIO == 12) {
            Globals.getInstance().vpisiKronologijo("SIMULACIJA SCENARIJ 1.2: Worldline USPEH R:" + racunId + " M_REF:" + simCheck.getPaymentSolutionReference() + " -> umetno pretvarjam v RESULT_CANCELED (auto LAST_TRANSACTION)");
            simForceNegative = true;
            simForceAutoLast = true;
            resultCode = android.app.Activity.RESULT_CANCELED;
            data = null;
        }
    }
}
// === END TODO REMOVE SIMULATION ===
```

In v `else` veji (`resultCode != RESULT_OK`) metode `handleSixTapResult(...)` začasno zamenjaj pogoja:

```java
boolean useRecovery = g.isSixTapManualLast() || g.isSixTapAutoLast() || simForceNegative;
boolean doAutoLast = (g.isSixTapAutoLast() || simForceAutoLast) && !simSuppressAutoLast;
if (useRecovery) {
    ...
    if (doAutoLast) {
        ...
    } else {
        ...
    }
}
```

---

## Postopek testiranja (eden po eden)

Po vsaki spremembi `SIMULATE_WORLDLINE_SCENARIO` zgradi APK:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

### Test 1 — Scenarij 1.1 (`SIMULATE_WORLDLINE_SCENARIO = 11`)
1. Odpri nov račun in sproži Worldline plačilo (iz **Naročil** ali **Plačil**).
2. Na Worldline uspešno zaključi plačilo s kartico.
3. RosKasa prejme simuliran `RESULT_CANCELED` in prikaže opozorilo (račun ostane odprt).
4. Ponovno klikni na kartično plačilo:
   - **1. klik:** prikaže opozorilo (`SIXTAP ESKALACIJA 1`).
   - **2. klik** (ali tipka `Tap On`): samodejno pokliče `WPI_SVC_LAST_TRANSACTION` (`SIXTAP ESKALACIJA 2`), poknjiži `setSlipEma` + `setRacun`, izpiše račun in pobriše flage.

### Test 2 — Scenarij 1.2 (`SIMULATE_WORLDLINE_SCENARIO = 12`)
1. Odpri nov račun in sproži Worldline plačilo.
2. Na Worldline uspešno zaključi plačilo s kartico.
3. Ob vrnitvi v RosKasa se **takoj samodejno** (izven callbacka, brez rekurzije) sproži `WPI_SVC_LAST_TRANSACTION`, poknjiži `setSlipEma` + `setRacun`, izpiše račun in pobriše flage.

### Test 3 — Scenarij 2 (`SIMULATE_WORLDLINE_SCENARIO = 2`)
1. Odpri nov račun in sproži Worldline plačilo.
2. Na Worldline zaključi plačilo (Test A: uspešno, Test B: preklicano).
3. Ob vrnitvi v RosKasa se aplikacija umetno zapre (`killProcess`), v `SixTapRecoveryPrefs` ostane `PendingPayment`.
4. Ponovno zaženi RosKasa in se prijavi:
   - **Test A (uspešno na Worldline):** `MainActivity.tapOnRecoverExecute()` samodejno sproži `WPI_SVC_LAST_TRANSACTION`, poknjiži `setSlipEma` + `setRacun`, izpiše račun in pobriše flage.
   - **Test B (preklicano na Worldline):** `MainActivity.tapOnRecoverExecute()` sproži `WPI_SVC_LAST_TRANSACTION`, dobi negativen odgovor in pobriše flage.

---

## Kontrolni seznam po vsakem testu

- [ ] **Kronologija:** prisotni zapisi `SIXTAP LAST_TRANSACTION USPEH` (ali `NEUSPEH`/`PREKLICAN`), `SIXTAP setSlipEma USPEH` (brez `NAPAKA GS0054`) in `ClearAllRecoveryData`.
- [ ] **Oracle `SLIP_EMA`:** obstaja nov zapis za `STEVILKA_RACUNA` z veljavnim `STRM_ID`, `STEVILKA_KARTICE`, `CARDNUMBER`, `AUTHNUMBER`, `STTYPE` (<= 19 znakov).
- [ ] **Oracle `RACGLAVA` / `RACPLACI`:** račun ima `STATUS = 2`, fiskalne podatke (`F_OZNAKA_DU`, `F_PODPIS`, `F_STEVILKA_RACUNA`) in natanko 1 plačilo z `M_REF` (brez dvojnega knjiženja).
- [ ] **Naslednja transakcija:** naslednje Worldline plačilo steče normalno brez opozoril/blokad (potrditev, da so bili vsi registri/flagi pobrisani).

---

## Čiščenje po končanem testiranju

Po uspešnih testih odstrani vse bloke označene s `// === TODO REMOVE SIMULATION AFTER WORLDLINE TESTS ===` iz:
- `SixTapPaymentService.java`
- `PlacilaFragment.java`
- `NarocilaFragment.java`

in preveri, da iskanje ne vrne zadetkov:

```powershell
Get-ChildItem -Path "app\src\main\java" -Recurse -Filter "*.java" | Select-String -Pattern "SIMULATE_WORLDLINE_SCENARIO|SIMULACIJA SCENARIJ"
```
