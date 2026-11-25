# Navodila za zagon regresijskih testov (Kasa aplikacije)

Ta dokument opisuje, kako na poziv zaženete avtomatski regresijski testni paket (**TC01–TC10**) in Oracle SQL preverjanje za vse tri Kasa aplikacije:

1. **RosKasaLight16** (`C:\GeminiCli\Delphi\RosKasaLight16`) — **Android64 Debug** (preko Autopilot)
2. **RosKasa** (`C:\GeminiCli\RosKasa`) — **Android Java Debug** (preko Gradle `androidTest`)
3. **RosKasaMobile2027** (`C:\GeminiCli\Delphi\RosKasaMobile2027`) — **Win32 Debug** (preko Autopilot)

---

## 1. Predpogoji

- **Oracle baza:** `GASTS/GASTS@ROS`, kodna tabela `EE8MSWIN1250` (`NLS_LANG=SLOVENIAN_SLOVENIA.EE8MSWIN1250` — skripta `run_sql_check.ps1` ga nastavi samodejno).
- **Android naprava (za `RosKasaLight16` in `RosKasa`):**
  - Telefon (npr. Pixel 8) priključen preko USB z omogočenim *USB Debugging* in odklenjenim zaslonom.
  - Preverjanje povezave:
    ```powershell
    C:\Users\mm684\AppData\Local\Android\Sdk\platform-tools\adb.exe devices
    ```
- **Fiksni testni podatki** so definirani v `C:\GeminiCli\RosKasa\tests\regression\test_dataset.json`:
  - PIN: `9999`, Mize: `Miza 58`, `Miza 59`, `Miza 60`
  - Hotel kredit: soba `205` (`ŠKRINJAR EVA`, `GOST_PRIJAVA_ID=374758`)
  - Partnerja: `ROS D.O.O.` (`41125`), `PRVA OSEBNA ZAVAROVALNICA D.D.` (`654330`)
  - Artikli: `809010081` (Paket Kremna rezina), `318130001` (Belo Goriška Brda — lestvica), `315070022` (Pivo Guinnes), `315070008` (Pivo Laško)
  - Plačila: `1` (Gotovina), `5` (Kred. k. ročno), `12` (Dobavnica), `31` (Gost hotela), `99` (Popust)

### Pregled scenarijev (`TC01`–`TC10`)

| Oznaka | Opis scenarija |
| :--- | :--- |
| `TC01` | Enostavno naročilo (`Pivo Laško`) + POST + `Gotovina` (`1`) + izpis |
| `TC02` | Paket (`Paket Kremna rezina`) + POST + `Kred. k. ročno` (`5`) + izpis |
| `TC03` | Pijača z lestvico (`Belo Goriška Brda 0,25`) + POST + `Gost hotela` (`31`, soba `205`) + izpis |
| `TC04` | Naročilo + `Dobavnica` (`12`) + partner `ROS D.O.O.` (`41125`) + izpis |
| `TC05` | Naročilo + `Dobavnica` (`12`) + partner `PRVA OSEBNA ZAVAROVALNICA` (`654330`) + izpis |
| `TC06` | `Popust 99` (`10%` na celoten račun) + doplačilo z gotovino + izpis |
| `TC07` | Popust `10%` na 1 pozicijo + gotovina + izpis |
| `TC08` | `Združi` dva računa (`Miza 58` + `Miza 59`) + `Split` (`2x Pivo Laško` na nov račun `Miza 58_1`) |
| `TC09` | `Storno` računa iz `TC01` **z ustvarjanjem novega računa** |
| `TC10` | `Storno` računa iz `TC02` **brez ustvarjanja novega računa** |

---

## 2. Ročni zagon testov (iz PowerShell-a)

Vse ukaze poganjajte iz mape `C:\GeminiCli\RosKasa`:

```powershell
cd C:\GeminiCli\RosKasa
```

### Korak 0 (priporočeno pred zagonom): Ponastavitev odprtih testnih računov

Briše samo **odprte** (`STATUS = 1`) testne račune za `KASIRAL = 9999` na mizah `Miza 58/59/60`:

```powershell
.\tests\regression\run_sql_check.ps1 -Step 03 -AppLabel reset
```

---

### A) RosKasaLight16 (Android64 Debug na telefonu)

1. Prepričajte se, da je `RosKasaLight16` (Debug build z `AUTOPILOT`) nameščen in zagnan na telefonu.
   *(Če ste spremenili Delphi kodo, jo najprej prevedite v Debug za Android64 in namestite na telefon.)*
2. Zaženite vse scenarije (`TC01`–`TC10`):
   ```powershell
   python .\tests\regression\run_roskasalight_suite.py
   ```
   Ali samo izbrane scenarije (npr. `TC02` in `TC03`):
   ```powershell
   python .\tests\regression\run_roskasalight_suite.py TC02 TC03
   ```
3. Preverite rezultate v Oracle bazi:
   ```powershell
   .\tests\regression\run_sql_check.ps1 -Step 02 -AppLabel RosKasaLight16
   ```

---

### B) RosKasa (Android Java na telefonu)

1. Telefon naj bo odklenjen in priključen preko USB.
2. Zaženite celoten `androidTest` paket (`TC01`–`TC10`):
   ```powershell
   .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=si.ros.RosKasa.RosKasaRegressionTest"
   ```
   Ali samo posamezen test (npr. `tc03_lestvicaGostHotela`):
   ```powershell
   .\gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=si.ros.RosKasa.RosKasaRegressionTest#tc03_lestvicaGostHotela"
   ```
3. Preverite rezultate v Oracle bazi:
   ```powershell
   .\tests\regression\run_sql_check.ps1 -Step 02 -AppLabel RosKasa_Android
   ```

---

### C) RosKasaMobile2027 (Win32 Debug na Windows)

1. *(Po spremembi Delphi kode)* ponovno prevedite Win32 Debug:
   ```powershell
   cmd /c "call `"C:\Program Files (x86)\Embarcadero\Studio\37.0\bin\rsvars.bat`" && msbuild `"C:\GeminiCli\Delphi\RosKasaMobile2027\RosKasaMobile.dproj`" /t:Build /p:Config=Debug /p:Platform=Win32 /v:minimal"
   ```
2. Zaženite aplikacijo in testni paket (`TC01`–`TC10`):
   ```powershell
   $p = Start-Process -FilePath 'C:\GeminiCli\Delphi\RosKasaMobile2027\Win32\Debug\RosKasaMobile.exe' -WorkingDirectory 'C:\GeminiCli\Delphi\RosKasaMobile2027\Win32\Debug' -PassThru
   Start-Sleep -Seconds 4
   try {
       python .\tests\regression\run_roskasalight_suite.py --win32
   } finally {
       if ($p -and !$p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
   }
   ```
   *(Za posamezne scenarije dodajte imena na konec, npr. `python .\tests\regression\run_roskasalight_suite.py --win32 TC07 TC08`.)*
3. Preverite rezultate v Oracle bazi:
   ```powershell
   .\tests\regression\run_sql_check.ps1 -Step 02 -AppLabel RosKasaMobile2027
   ```

---

## 3. Oracle SQL regresijske skripte (`01`–`04`)

Vse SQL skripte so v `C:\GeminiCli\RosKasa\tests\regression\sql\` (kodiranje `EE8MSWIN1250`, brez UTF-8 BOM):

| Korak | Ukaz | Namen |
| :---: | :--- | :--- |
| `01` | `.\tests\regression\run_sql_check.ps1 -Step 01 -AppLabel seed` | Preveri obstoj testnih artiklov, plačil, partnerjev in gosta sobe `205` v bazi |
| `02` | `.\tests\regression\run_sql_check.ps1 -Step 02 -AppLabel check` | Izpiše testne račune in samodejno preveri **PASS / FAIL** (`RACGLAVA`, `RACPOZIC`, `RACPLACI`, `FISKALIZACIJA`, `RACIZPISAN`, `KRONOLOGIJA`) |
| `03` | `.\tests\regression\run_sql_check.ps1 -Step 03 -AppLabel reset` | Pobriše samo odprte (`STATUS=1`) testne račune na `Miza 58/59/60` za `KASIRAL=9999` |
| `04` | `.\tests\regression\run_sql_check.ps1 -Step 04 -AppLabel cleanup` | Predogled čiščenja vseh testnih zapisov (privzeto varni `ROLLBACK`) |

Vsi izpisi SQL kontrol in JSON poročila se samodejno shranjujejo v `C:\GeminiCli\RosKasa\tests\regression\results\`.

---

## 4. Kako dati nalogo AI asistentu (Antigravity)

Ko želite, da teste zaženem jaz in preverim rezultate v Oracle bazi, lahko uporabite katerega od spodnjih ukazov (preprosto kopirajte v pogovor):

### Zagon za vse 3 aplikacije hkrati
> Poženi regresijske teste TC01–TC10 po navodilih iz `C:\GeminiCli\RosKasa\tests\regression\NAVODILA_REGRESIJSKI_TESTI.md` za vse 3 aplikacije (RosKasaLight16 na Androidu, RosKasa Java na Androidu in RosKasaMobile2027 na Win32). Pred vsakim zagonom naredi reset (`Step 03`), po vsakem zagonom pa Oracle SQL kontrolo (`Step 02`) in mi poročaj PASS/FAIL tabelo.

### Zagon samo za eno aplikacijo
- **RosKasaLight16 (Android):**
  > Poženi regresijske teste TC01–TC10 za RosKasaLight16 na Androidu (glej `NAVODILA_REGRESIJSKI_TESTI.md`) in preveri rezultate v Oracle bazi (`Step 02`).
- **RosKasa (Android Java):**
  > Prevedi in poženi `RosKasaRegressionTest` (TC01–TC10) za Android aplikacijo RosKasa ter preveri rezultate v Oracle bazi (`Step 02`).
- **RosKasaMobile2027 (Win32):**
  > Prevedi (Win32 Debug) in poženi regresijske teste TC01–TC10 za RosKasaMobile2027 (`--win32`) ter preveri rezultate v Oracle bazi (`Step 02`).

### Zagon samo izbranih scenarijev po popravku napake
> Poženi samo scenarije TC03, TC07 in TC09 na RosKasa (Android) in RosKasaMobile2027 (Win32) ter preveri v Oracle bazi, da je vse PASS.

---

## 5. Pomembne opombe

- **Varnost vašega ročnega testiranja:** Autopilot v `RosKasaLight16` in `RosKasaMobile2027` je vključen izključno z `{$IFDEF AUTOPILOT}` v konfiguraciji **Debug** (v **Release** se sploh ne prevede) in je v času vašega ročnega testiranja popolnoma pasiven.
- **POS terminal plačila:** Fizično prislanjanje kartice na POS terminalu ni del avtomatskega paketa — avtomatizirana so vsa ostala plačila (`Gotovina`, `Kred. k. ročno`, `Dobavnica` z iskanjem partnerjev, `Gost hotela` soba `205`, `Popust 99`).
