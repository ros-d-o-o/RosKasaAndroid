# Funkcionalna specifikacija referenčne Delphi aplikacije `RosKasaLight16` (Android obseg)

Ta dokument predstavlja popolno funkcionalno specifikacijo referenčne Delphi aplikacije **`RosKasaLight16`** (vključno s skupnimi enotami iz `RosKasaMobile2027` in `SkupniAndroid`, brez Windows-specifičnih perifernih naprav) po vseh 12 funkcionalnih sklopih in scenarijih delovanja. Služi kot **vir resnice** za razvoj in verifikacijo Java Android projekta `C:\GeminiCli\RosKasa`.

*Povezana dokumenta:* [REVIZIJA_DELPHI_VS_JAVA.md](file:///C:/GeminiCli/RosKasa/docs/REVIZIJA_DELPHI_VS_JAVA.md) | [TODO_FAZNI_PLAN.md](file:///C:/GeminiCli/RosKasa/docs/TODO_FAZNI_PLAN.md)

---

## Pregled referenčnih Delphi enot

* **Glavni projekt:** [RosKasaLight.dpr](file:///c:/GeminiCli/Delphi/RosKasaLight16/RosKasaLight.dpr)
* **Ključne enote:**
  * Glavni vmesnik in tok: [FormKasaMobile.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormKasaMobile.pas)
  * Podatkovni modul in SOAP logika: [DataGisOrder.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/DataGisOrder.pas)
  * Globalne nastavitve in `MOBINI`: [Globals.pas](file:///c:/GeminiCli/Delphi/RosKasaMobile2027/Globals.pas), [uMobIni.pas](file:///c:/GeminiCli/Delphi/SkupniAndroid/uMobIni.pas), [AppConsts.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/AppConsts.pas)
  * Prijava NFC: [FormNFClogin.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormNFClogin.pas)
  * Naročila & urejanje: [FormRacpozicEdit.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormRacpozicEdit.pas), [FormNumDialog.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormNumDialog.pas), [FormHodi.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormHodi.pas), [FormCrm.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormCrm.pas)
  * Plačila & posebni obrazci: [FormPartner.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormPartner.pas), [FormSobe.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormSobe.pas), [FormDN.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormDN.pas), [FormNapitnine.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormNapitnine.pas), [FormNacPlacHit.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormNacPlacHit.pas)
  * SoftPOS: [uPayten.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/uPayten.pas), [uSixTapPayment.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/uSixTapPayment.pas), [uSixTapResponseU.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/uSixTapResponseU.pas)
  * Računi & operacije: [FormStornoRazlog.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormStornoRazlog.pas), [FormSplit.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormSplit.pas), [FormZdruziRacune.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/FormZdruziRacune.pas)
  * Tiskanje & kronologija: [uPrintData.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/uPrintData.pas), [PrintAll.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/PrintAll.pas), [KronologijaManager.pas](file:///c:/GeminiCli/Delphi/RosKasaLight16/KronologijaManager.pas)

---

## Sklop 1: Zagon, registracija naprave, `MOBINI` in lokalni predpomnilnik

1. **Prvi zagon (ni lokalne konfiguracije `RosKasaConfig.bin`):**
   * Registracijski zaslon: `URL`, `MOBILE_ID`, `Username`, `Password`.
   * Klic `getOsebaToken(username, password)` → `token` → klic `aktivirajMobile(MOBILE_ID, token)` (ob napaki se prekine).
   * Shranjevanje `MOBILE_ID`, `URL`, `Token` v lokalno shrambo in zahteva za ponovni zagon (`SRestartApplication`).
   * Google Play demo bypass: `DemoGooglePlay` / `1DemoApprove6840` ali `edurl = 'demo'` → demo URL + `MOBILE_ID = 6`.
2. **Normalen zagon (konfiguracija obstaja):**
   * Preverjanje omrežne povezave (`CheckPovezava`). Če ni interneta → prikaz napake brez nadaljevanja.
   * Klic `getAppConfig(MOBILE_ID, token)` (`SetupIzWeba(True)`):
     * Fiksna polja `MobileSetup` (podjetje, PE, PM, `F_POS_ID` vs `TIPKE_POS_ID`, `TOCILNICA_ID`, `KUHINJA_ID`, `HIS_OBRAT`, `HIS_DESTINACIJA`, ESC kode, širina papirja: **če je `STEVILOZNAKOV = 48`, se korigira na `42`**).
     * **3-stopenjsko plastenje `MOBINI`**: `MOBINI0` → `MOBINI` → `MOBINI2`. Ključi so case-insensitive (`SameText`), ob podvojitvi zmaga zadnja vrednost.
     * Vedno sveže polnjenje iz `MobileSetup`: `OSEBE`, `PRIORITETE_PROJEKTOV`, `TARIFE`, `MOBILE_SETUP_MIZE`.
   * Klic `IniPodatkov(True)`: nalaganje 6 večjih šifrantov (`Cenik` + `CenikVrVr` + `CenikVrCene`, `HitreTipke`, `NacPlac`, `Dodatki`, `StornoRazlogi`, `LojalnostPopust`).
3. **Lokalni predpomnilnik (`CENIKLOKALNO = 'D'`, `CENIKSTDNIOBNOVA` privzeto 3 dni):**
   * Disk JSON predpomnilnik za vseh 6 večjih šifrantov (ne za `MobileSetup`). Če je starost datoteke `<= CenikStDniObnova` dni, preskoči SOAP prenos.
   * **Ročna prisilna osvežitev (`btnRacprenos`):** začasno razveljavi starost predpomnilnika (`-2`) in ponovno prenese vseh 6 šifrantov.
4. **Orientacija zaslona in zaklepanje UI:**
   * Samodejna prilagoditev portrait/landscape (`NastaviLayoutOrientacije`): v landscape funkcijske tipke desno (50 %), hitre tipke/plačila spodaj; v portrait vertikalno; dinamična višina gumbov iz širine zaslona.
   * `DisableEkran` / `EnableEkran` z varnostnim timerjem (`TIMERENABLEEKRAN`, privzeto 10 s), ki ob zataknjenem SOAP klicu zapiše razlog v kronologijo in samodejno odklene zaslon.
   * Android gumb *Back*: potrditveni dialog za izhod + pošiljanje kronologije na strežnik.

---

## Sklop 2: Avtentikacija (PIN, NFC), odjava in sistem pravic

1. **Prijava s PIN-om (`NajdiOseboZaPin`):**
   * Iskanje osebe v `OSEBE` po `PIN`. Prazna ali `'0'` koda zavrnjena.
   * **Omejitev poskusov:** po 3 neuspešnih poskusih (4. klik) zapre aplikacijo.
   * **Potek veljavnosti PIN-a (`CHECKPINPOTEKEL = 'D'`):** če `DaysBetween(Danes, DATUM_SPREMEMBEPIN) > VELJAVNOSTPIN` → zavrne prijavo.
   * **Obvezna pravica `SLahkoDelaSKaso`:** brez nje prijava ni dovoljena.
2. **Prijava z NFC kartico (`FormNFClogin`, `vo = 1`):**
   * `Tag.getId()` → HEX (brez ločil) in decimalno (`Int64`). Iskanje po `KARTICA_ID` in `KARTICA2_ID` (najprej dec, nato HEX).
   * Preklop NFC → PIN (`btnPIN`): izklopi NFC za to prijavo (`PrvaPinPrijava = True`), da ne sproži ponovnega branja iz medpomnilnika.
   * Nadzor vklopa NFC preko `NFCPRIJAVA` in `ANDROIDNFCIZKLOP`.
3. **Akcije po uspešni prijavi:**
   * Pošlje lokalno shranjeno kronologijo (`Kronologija.json`).
   * Enkratni **`TapOnRecoverExecute`** (preveri nedokončano SixTap transakcijo in naloži račun z `namen = 99`).
   * **`Cena2VikendPrazniki`** (preveri vikend/petek/praznik in po potrebi zaklene preklop cenika).
   * Preklopi na zavihek **Mize**.
4. **Sistem privilegijev (`OsebiDovoljeno`):**
   * 1-based indeksiranje v `Pravice` preko `PRIORITETE_PROJEKTOV.POZICIJA_ID` (`Pravice[pozicija_id] = '1'`).
   * Ključne pravice: `SLahkoDelaSKaso`, `SVidiVseRacune`, `SVidiVsaNarocila`, `SLahkoStorniraRacun`, `SLahkoStorniraPozicijoRacuna`, `SStorniraPoslanoNarocilo` (override z `OVERRIDESTORNOPOSLAN`), `SpreprecimEditStornoPozicij`, `SLahkoPonovnoIzpiseRacun`, `SLahkoPreklopiCenik`, `SIzvedeFinancniPregled`, `SIzvedeKompletenFinancniPregled`, `SLahkoPozeneObracun`, `SvezanaKnjigaRacunov`.
   * `MaxPopOseba`: iz `OSEBE.PRIORITETA` (maksimalni dovoljeni % popusta).
5. **Odjava (`Logout`):**
   * Vidna le, če je `ODJAVA = 'D'` (`pLogout`). Pošlje kronologijo, počisti PIN/NFC stanja in `TekocaOseba`, ponovno aktivira NFC in vrne na prijavni zaslon.

---

## Sklop 3: Mize, rajoni in markerji

1. **Mize in rajoni:**
   * Mize iz `MOBILE_SETUP_MIZE`, urejene po `ZAP`.
   * Če je `RAJONI = 'D'` in unikatnih rajonov `> 1`: vrstica gumbov `"Vsi"` (`999`) ter `"R <id>"`. Začetni rajon je `RAJONDEFAULT`; izbrani rajon se **zapomni med sejo**.
   * Filter rajona vedno ohrani dinamične markerje (`RAJON = <izbran> OR RAJON < 0`).
2. **Zasedenost miz in dinamični markerji:**
   * Klic `getRacuniSeznam(status=1, pOseba, TekociStrm, Date-3)`, kjer je `pOseba = 0`, če `VidiVsaNarocila or OdpriVseRacune`, sicer `TekocaOseba`.
   * Zasedene mize se obarvajo rdeče (`Tag = RACUN_ID`).
   * Odprti računi s prostim markerjem (ki ni v `MOBILE_SETUP_MIZE`, nima `'Z:'`/`'ZDRU'` in `STORNO_RACUN_ID = 0`) se dinamično dodajo z `rajon = -99` in ostanejo vidni pri vsakem rajonu.
3. **Tap na mizo & markerji:**
   * Prosta miza (`RACUN_ID = 0`) → nov lokalni račun z `MARKER = naziv mize` → Naročila.
   * Zasedena miza (`RACUN_ID <> 0`) → `getRacuni` → Naročila.
   * Neshranjene spremembe (`prenos = 'N'` ali `RACUN_ID < 0`) se ob kliku na *Mize* / *Računi* / *Plačila* najprej samodejno pošljejo na strežnik (`postRacun`).
   * Prosti marker (`btnMarker`) in zamenjava markerja (`btnRacZMarker`, le za `STATUS = 1`, s kronologijo) validirata vnos s `PreveriMarker` (alfanumerično + presledki).

---

## Sklop 4: Naročila, cenik, hitre tipke, cenovna pravila in paketi

1. **Hitre tipke (`tblHitreTipke`):**
   * Mreža: telefon pokončno 5×5, ležeče 5×6, tablica 7×5.
   * Hierarhični drill-down: `NIVO4_ID < 0` odpre podskupino `abs(NIVO4_ID)`; prazna skupina prikaže le virtualni gumb nazaj (`<<< VIRTUAL` / `-1`).
   * Tipke uporabljajo `ENOTA_PRODAJE_ID` (prikaz `NAZIV [EP]` in posredovanje `ep`), `BARVA` in `DODATEK_ID` (`DODATNI_OPIS`).
   * Bližnjice plačil (`hotkey3..5`) in **Hitra gotovina** (preveri `getVerzijaOfRacglava` → gotovina za preostali saldo → `FISKALIZACIJA = 1` → `setRacun` → izpis).
2. **Seznam cenika (`lv_cenik`):**
   * Case-insensitive `LIKE` iskanje; `STANDARD = 'SKRIJ'` (`NIMOZNAIZBIRA = True`) skrije artikel; `CENA0DOVOLJENA` dovoli knjiženje cene 0, `CENA0CENIK = False` skrije artikle s ceno 0 iz seznama; povratek na hitre tipke resetira skupino na `1`.
3. **Cena 1 vs Cena 2, vikend, prazniki, `DNCENIK`:**
   * `MODELCENA2 = 1` (fallback na `Cena1`, če je `Cena2 = 0`) vs `MODELCENA2 = 2` (strogo `Cena2`). `CENA1 = 0` se pri parsanju NE sme prepisati s `CENA2`.
   * `CENA2VIKEND` (+ `PETEKWEND`) in `PRAZNIKI` (`getPrazniki(Date, PraznikiObrat)`) vklopita `CENA2AKTIVNA`; `CENA2PREKLOPOFF` zaklene ročni preklop; ročni preklop zahteva `SLahkoPreklopiCenik`.
   * `DNCENIK = True`: če ima račun `DN_ID`, prepiše ceno artikla (in sestavin paketov) iz `tblCenikVrCene(DN_ID, NIVO4_ID)`.
4. **Ročni vnos cene (`pomcena = -1` in `-2`):**
   * Dovoljen le, če `pompaket <> 2`, (`VnosCeneZaVseINI = True` ali `VidiVsaNarocila`) in naziv ne vsebuje `'KAVCIJA'`.
   * `-1`: samo pozitivna cena (`> 0`); `-2`: dovoljen tudi negativen predznak (`< 0`).
5. **`NACIN_PRODAJE`, lestvice polnjenja (EP) in `CenaPolnjenje`:**
   * `[1, 3, 4, 9, 10]` (točene): `0.1, 0.125, 0.2, 0.25, 0.3, 0.5, 1`.
   * `2` (žgane): `0.03, 0.05` + `POLNJENJE` iz cenika.
   * `5` (kg): EP `>= 0.01`, decimalna količina dovoljena. `6` (kos): prisili `EP = 1`, celoštevilska količina.
   * Pri `EP < 1` so za količino dovoljena samo **cela števila** (razen `NACIN_PRODAJE in [5, 9]`).
   * **Formula `CenaPolnjenje`:** ko je `pomep <> 1` in `pompolnjenje <> 0`:
     * `CenaPolnjenje = 'D'` → cena na enoto = `osnovnaCena / pompolnjenje`.
     * `CenaPolnjenje <> 'D'` → deli s `pompolnjenje` samo, če `(pomep <> pompolnjenje) and (EM = 1)`.
6. **Paketi (`pompaket = 1` fiksni, `pompaket = 2` Mix & Match):**
   * `pompaket = 1`: razbije sestavine iz `CENIKVRVR` z istim `PAKET_NIVO4_ID` in naključnim `PAKET_DISTINCT`, `PAKET_KOL`, `KOLICINA = sestavina.kol * kol * paketkol`, `CENA2` (če `CENA2AKTIVNA`), davčna stopnja po `TARIFA_ID` vsake sestavine, in parska izravnava na zadnji vrstici (upoštevajoč `comp.kolicina`).
   * `pompaket = 2`: izbirni dialog sestavin iz `CENIKVRVR`.
   * Združen prikaz, urejanje in brisanje vseh sestavin paketa po `PAKET_DISTINCT`.
7. **Združevanje vrstic, `IZVOR_STRM_ID`, `LOKATOR`, `_NeNarocaj`:**
   * Enak artikel (`NIVO4_ID`, `EP`, `CENA`, ne-paket) se združi, razen če ima `DODATNI_OPIS`, `HOD`, ali `NOVAVRSTICANAROCILA = True` oz. darilni bon.
   * `IZVOR_STRM_ID`: iz cenika; če 0 → `KUHINJA_ID` za `NIVO1_ID = 2` (hrana), sicer `TOCILNICA_ID`.
   * `_NeNarocaj = True` samo če velja (`PrintamNarocila` brez `PRINTBLOKINNAROCILO`, razen pri stornu z `PrintamStornoNarocila`) ali je izklopljen kanal `NePrintamNarT1..4` glede na `tblCenik.IZPIS_NAROCILA`.

---

## Sklop 5: Urejanje pozicij, popusti (pozicijski, Popust 99, lojalnost), hodi in CRM

1. **`FormRacpozicEdit`:**
   * Količina (`+1`/`-1`, numerični vnos, `EDITKOLICINAADD`), zaklep EP pri `STATUS > 0`, popust `%` omejen na `0..MaxPopOseba`, opombe iz `tblDodatki` (max 240 znakov), hod, receptura (`getReceptura` ali sestavine paketa).
   * Formula: `ZNESEK` = bruto (`KOL * EP * CENA`), `CENA_NABAVNA` = `%`, `ZNESEK_POPUST` = `Round((CENA * (pop%/100)) * (EP * KOL), -2)`, neto = `ZNESEK - ZNESEK_POPUST - ZNESEK_LOJALNOST`.
2. **Brisanje pozicij & zaklepanje računa:**
   * Zaklep celotnega urejanja naročila (`ZakleniEdit`), če: `STATUS >= 2`, `RACPLACI > 0` (tudi delno plačilo!), `STORNO_RACUN_ID > 0`, `LOJALNOST_ID > 0`, `AKCIJA_ID > 0`, `UREJAMSTORNO = 0` ali `BKARTA_ID`.
   * Če `NiStornoIzpisanegaNarocila = True` in `POZICIJA_ID > 0`: prepovedano brisanje in zmanjševanje količine (povečanje dovoljeno).
   * Soft-delete (`RowDeleted = True`) za `RACUN_ID > 0`, fizično brisanje za `RACUN_ID < 0`.
3. **`Popust 99` (`PLACILO_ID = 99`):**
   * `PreveriPopustNaRacunu`: blokiran, če obstajajo plačila (`RACPLACI > 0`), drug popust, lojalnost ali akcija (`AKCIJA_ID > 0`).
   * Vnos `%` ali `€`, omejitev `<= MaxPopOseba`, porazdelitev po pozicijah (`Popust99Proporcialno`, neto davek), zapis v `RACPLACI` (`PLACILO_ID = 99`, `DELNI_ZNESEK = 0`, `STATUS = %`, `ZNESEK = znesekPopusta`), obratna operacija ob brisanju (`BrisiPopustNaRacun`).
4. **Lojalnost (`getLojalnostna`, `setLojalnost`) & Hodi (`FormHodi`, `1..8`, `TekociHod`, `natisniHod`).**
5. **CRM (`FormCrm`):** `searchGost`, `crmInfo` (vključno z `OdstraniVodilneNicle` za 30-znakovne trojne odčitke), `gdprLog`, zapis `CRM_ID`/`CRM_ST_KARTICE`, samodejni CRM popust `99`, `crmGetStTock`/`crmTocke` in izpis stanja kapljic na računu.

---

## Sklop 6: Plačila, pravila kombiniranja, vračilo in fiskalizacija

1. **Plačilne metode (`METODA`):**
   * `1` (Gotovina/standard): delna plačila, zavrnitev preplačila, izračun vračila gotovine (`VraciloNum` – ločeno polje prejete gotovine).
   * `3` (Partner/Dobavnica/Reprezentanca/Fiksni kupec): `GetPartner`, Reprezentanca (`TIP_PARTNER = 5`, obvezen izbor iz baze), interni seznami (`8, 31, 90`), validacija ročnega vnosa, **VIES EU DDV** (`ViesVatClient`), avtomatski rabat partnerja (zapis vrstice `PLACILO_ID = 99` v `RACPLACI`), `STNAROC`, `KUPEC_ID`, splošni fiksni kupec (`JePlaciloFiksniKupec`).
   * `6` (Hotel kredit / Sobe): `getKartprij` (`HIS_OBRAT`, `HISOBRATI`, `HIS_DESTINACIJA`), NFC kartica gosta (**HEX UID**, `vo = 2`), **`TIP_KART = 'S'` (`Blokada HK`) strogo onemogoči izbor**, `TIP_KART = 'X'` (`v odhodu`), Skupine (`getListaSkupin`, `HIS_CENIK_AI`), Obroki (`getObroki`), ekskluzivno plačilo (brez delnih plačil/mešanja), `FISKALIZACIJA = 2`.
   * `14` (SoftPOS kartice): omejitev 1 kartičnega plačila istega `PLACILO_ID` na račun pri aktivnem SoftPOS/stornu.
   * `FormDN` (Delovni nalogi & 3-vrstična opomba), `FormNapitnine` (`NAPITNINAROS`), `FormNacPlacHit` (HIT Reklama & CRO napojnice `setNapitnina`).
2. **Validacije & kombiniranje:**
   * `FiskalnoEnako` (privzeto `True`): prepoved mešanja fiskalnih in nefiskalnih plačil (razen `99`).
   * Ekskluzivnost Hotel kredita in Dobavnice (razen Popust `99`).
   * Negativni zneski samo pri `KavcijaNaRacunu`; znesek `0` dovoljen le pri `CENA0DOVOLJENA` (gotovina) ali `HOTELKREDIT0` (HK).
   * **Pred-plačilni `getVerzijaOfRacglava(RACUN_ID)`:** če je na strežniku novejša verzija, **prekine** plačilo in osveži račun.
3. **Fiskalizacija & brisanje plačil:**
   * Če je vsaj eno plačilo (`<> 99`) fiskalno → `FISKALIZACIJA = 1`, sicer `2` (`null` v `setRacun`).
   * Brisanje strogo prepovedano, če `F_STEVILKA_RACUNA <> 0` ali `STORNO_RACUN_ID <> 0`.
   * Ob brisanju metode `3` samodejno zbriše pripadajoči Popust `99`; ob brisanju metode `14` najprej izvede SoftPOS `void`/`CANCEL`/`REFUND` + `setSlipEma`; soft-delete za `RACUN_ID > 0`, hard-delete za `RACUN_ID < 0`.

---

## Sklop 7: SoftPOS integracije (Payten & Worldline SixTap)

1. **Payten (`uPayten.pas`):**
   * `amount = Round(amt * 100)`, `RequestCode = 1` guard, `PayTenintransaction` zapora (sprostitev šele v callbacku).
   * 5-stopenjski stanjski avtomat (`NONE` → `PENDING_POS` → `POS_CONFIRMED` → `LOCAL_RECORDED` → `COMPLETED`) v `SharedPreferences`.
   * Ob `POS_CONFIRMED` / `LOCAL_RECORDED` za isti račun **ne** sproži novega POS intenta, temveč dokonča knjiženje iz shranjenega odgovora (`RecoverConfirmedPayTenTransaction`).
   * Ob `PENDING_POS`: `< 60 s` trda blokada; `60–180 s` dialog natakarju (DA → ročna obnova `MANUAL-<id>`, NE → ponovni zagon); `> 180 s` samodejni reset.
   * `void`: `DELNI_ZNESEK + NAPITNINA`, `authorizationCode = M_REF`, `setSlipEma('REF')` z negativnimi zneski.
2. **Worldline SixTap (`uSixTapPayment.pas`):**
   * Operacije: `WPI_SVC_PAYMENT`, `WPI_SVC_CANCEL_PAYMENT` (če `ZadnjiSixRacunId = RACUN_ID`), `WPI_SVC_REFUND`, `WPI_SVC_LAST_TRANSACTION`.
   * GUID `WPI_SESSION_ID` se zapiše v **`RACPLACI.A_VRSTA_STROSKA`**.
   * Case-insensitive JSON parsanje; napitnine (`NAPITNINAROS` prek `FormNapitnine` in `NAPITNINAPOS` iz odgovora).
   * Ob `ResultCode <> RESULT_OK`: shrani recovery → `SixTapAutoLastTransaction` (takoj `LAST_TRANSACTION`) ali `SixTapManualLastTransaction` (opozorilo za tipko *Tap On*).
   * **3-stopenjska eskalacija (`KEY_RECOVER_RETRY_COUNT`):** ob poskusu novega plačila z nerešeno transakcijo: `1` = blokada + opozorilo, `2` = samodejni klic preverjanja statusa, `3` = prisilna ponastavitev.
   * Obnova ob prijavi (`TapOnRecoverExecute`): po prijavi in osvežitvi `getAppConfig` naloži račun (`namen = 99`) in po potrebi sproži `LAST_TRANSACTION` oz. dokonča `POS_CONFIRMED` (vključno s `FISKALIZACIJA = 1` in `STATUS = 2`).
   * Zasilni reset: gumb *Test tiskanja* počisti pending/recovery stanja za oba SoftPOS sistema.

---

## Sklop 8: Seznam računov, predogled in Storno

* Odprti (`STATUS = 1`) vs Izpisani (`STATUS = 2, 4`), filter po osebi (`SVidiVseRacune`/`SVidiVsaNarocila`/`OdpriVseRacune`), časovna omejitev `IZPISANIVIDNIDO`, skrivanje zaključenih storno računov (`STORNO_RACUN_ID <> null && STATUS >= 2`).
* Predogled za `STATUS >= 2`; ponovni izpis kopije preverja `SLahkoPonovnoIzpiseRacun`, inkrementira `STKOPIJ` in uporabi celotno kaskado (`PrintDataHandler`).
* Storno (`SLahkoStorniraRacun`): pri `SpreprecimEditStornoPozicij` preskoči izbiro razloga in pošlje `NovoNarocilo = True`, `NovoNarocilo_UREJAMSTORNO = 0` (novi račun zaklene pozicije); sicer prikaže `FormStornoRazlog` (upošteva `RAZLOGSTORNO`) + vprašanje za nov račun.
* **SoftPOS storno (`1 < STATUS < 4`):** izvede POS `void`/`CANCEL`/`REFUND` + `setSlipEma` *pred* `setStornoRacuna`. Za **`STATUS >= 4`** se POS storno **NE** izvede!
* Po `setStornoRacuna`: samodejno naloži in natisne `data1` (storno račun).

---

## Sklop 9: Delitev (Split) in Združevanje računov

* **Split (`FormSplit`):**
  * Vstopni pogoji: `STATUS = 1`, `RACPLACI = 0`, brez `DN_ID`, `AKCIJA_ID`, `BKARTA_ID`, in `> 1` postavka (ali količina `> 1`).
  * Do **5 ciljnih računov** (`R1..R5`), cele količine po `1`, decimalne v celoti, paketi kot celota po `PAKET_DISTINCT`.
  * **Kritična pravila shranjevanja:** na zmanjšanih pozicijah originala nastaviti **`STATUS = novaKolicina`**, **`STATUS_POZ = 9`** in **`_NeNarocaj = True`**; izpraznjene pozicije na shranjenem računu dobijo `RowDeleted = True`, `_NeNarocaj = True`, `STATUS_POZ = 9`; novi računi dobijo `<marker>_<1..5>`, `STATUS = KOLICINA`, `_NeNarocaj = True`.
* **Združevanje (`FormZdruziRacune`):** `>= 2` odprta računa (`STATUS = 1`), marker `'Z: ID1/ID2/...'` (max 19 znakov), SOAP `zdruziRacune`, osvežitev seznama.

---

## Sklop 10: Tiskalniški podsistem, FURS QR, kopije in dodatni izpisi

* **Kaskada `PrintDataHandler`:** `akcijaSetKuponiRacuna` (le `STATUS = 1`, ne-storno) → `akcijaNaziv` → `akcijaGet` → `getSlipEma2` (deduplikacija po `ACQTRANSREF`, upošteva `PRINTAMSLIPNARACUN`; za storno račun `'REF'` slip iz `getSlipEma2(STORNO_RACUN_ID)`) → `getPrijava` / `CenikAiNaziv` → `GetPartner` (+ davčna skupina) → `StornoRazlog` → `crmGetStTock`/`crmTocke`.
* **Kopije:** `max(NACPLAC.STKOPIJ)`, prvi izpis original, nadaljnji `Kopija št. 1, 2, ...`, pravilno povečanje `RACGLAVA.STKOPIJ`.
* **Enotna BT seja:** UTF-8, OptiPos/InnerPrinter init (`$1C $26 $1C $43 $FF`), enojni `escCut`, modalna pavza (`TISKANJEPAVZA`) ali `PRINTBLOKPAVZA`.
* **Dodatki (`STATUS < 2`, ne-storno):** Voucher (`PRINTVOUCHER`), Naročilni blok (`PRINTAMNAROCILA` – Blok 1 `IZPIS_NAROCILA = 1` / Blok 2 `IZPIS_NAROCILA > 1` + lokator), Akcije QR (`AKCIJE`).
* **FURS QR:** SLO 60-mestna Mod10 iz **dejanskega datuma in ure računa** (`racun.Datum` + `racun.Ura`), HR `CROFISK` URL, Epson (`GS ( k`) in Star (`27-29-121-...`) ESC/POS.
* **Arhiv:** `insertIzpisan` s `CAS_IZPISA = now + (stKopijPrej + 1)` sekund → prehod v `STATUS = 2`.

---

## Sklop 11: Inkaso, Obračun in Kronologija

* **Inkaso:** `SIzvedeFinancniPregled` vs `SIzvedeKompletenFinancniPregled` (`osebaId = 0`), `getReport('Zakljucni1')` Base64 → BT izpis; ob `NAPITNINE = True` še `getNapitninaSkupaj` (+ `NAPITNINEPROCENTKUH`).
* **Obračun:** `OBRACUNDOVOLJEN`, `SLahkoPozeneObracun`, `OBRACUNANDROID`, `narediObracun(1, Strm, 0, ReportFormat)`, preskok tiska pri `OBRACUNBREZIZPISA`, prikaz odprtih računov ob napaki.
* **Kronologija:** `MAID:<MOBILE_ID>`, omejitev 950 znakov, offline shranjevanje na disk in ponovno pošiljanje ob ponovni povezavi/prijavi.

---

## Sklop 12: SOAP konkurenca (`VERZIJA_ZAPISA`) in `__OriginalObject`

* Shranjevanje originalnega `RacunTp` ob vsakem `getRacuni`/`setRacun` in pošiljanje `__OriginalObject` (glava, pozicije, plačila) za delta izračun na strežniku.
* Optimistično zaklepanje s `VERZIJA_ZAPISA` + pred-plačilni `getVerzijaOfRacglava` + zaščita pred `'race'` brez izgube plačila + ohranjanje `RACGLAVA.PLACANO`, če strežnik vrne prazen `RACPLACI`/0.
