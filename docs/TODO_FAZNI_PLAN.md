# RosKasa Android — Fazni TODO načrt (na osnovi revizije `RosKasaLight16`)

*Referenčna dokumenta:*
* Specifikacija Delphi: [DELPHI_SPECIFIKACIJA_ROSKASALIGHT16.md](file:///C:/GeminiCli/RosKasa/docs/DELPHI_SPECIFIKACIJA_ROSKASALIGHT16.md)
* Podrobna primerjalna matrika: [REVIZIJA_DELPHI_VS_JAVA.md](file:///C:/GeminiCli/RosKasa/docs/REVIZIJA_DELPHI_VS_JAVA.md)

## Pregled napredka po fazah

| Faza | Opis | Status | Napredek |
|---|---|---|---|
| **P0** | Kritični finančni, fiskalni in SoftPOS popravki (pred produkcijo) | ✅ Končano | 7 / 7 |
| **P1** | Cenovna, naročilna in davčna točnost + sistem pravic | ✅ Končano | 6 / 6 |
| **P2** | Tiskanje, mize/rajoni, avtentikacija, predpomnilnik in plačilne kontrole | ✅ Končano | 4 / 4 |
| **P3** | Manjkajoči razširjeni moduli iz `RosKasaLight16` (po prioriteti strank) | ✅ Končano *(brez FormNacPlacHit in CROFISK)* | 7 / 7 |

---

## Faza P0 — Kritični finančni, fiskalni in SoftPOS popravki (OBVEZNO PRED PRODUKCIJO)

- [x] **P0.1 FURS QR datum/čas (`RacunPrintBuilder.java:529`)**
  - Podati dejanski `racun.getDatum()` + `racun.getUra()` v `FursQrHelper.pripraviFursKodo(...)` namesto `new Date()`.
- [x] **P0.2 Storno obračunanega računa (`STATUS = 4`) s SoftPOS (`RacuniFragment.java:574-635`)**
  - Dodati pogoj `status > 1 && status < 4`, da se za `STATUS >= 4` POS `void`/`CANCEL_PAYMENT`/`REFUND` ne izvaja.
- [x] **P0.3 Payten Crash Recovery (`PaytenPaymentService.java`, `PaymentRecoveryManager.java`, `PlacilaFragment.java`)**
  - Implementirati 5-stopenjski stanjski avtomat (`NONE` → `PENDING_POS` → `POS_CONFIRMED` → `LOCAL_RECORDED` → `COMPLETED`).
  - Ob `POS_CONFIRMED` / `LOCAL_RECORDED` za isti račun izvesti recovery brez ponovnega bremenjenja kartice.
  - Implementirati `60s` / `180s` časovna varovala z dialogom za natakarja (`MANUAL-<id>`) in blokado `isPayTenIntransaction()`.
- [x] **P0.4 Worldline SixTap popravki (`PlacilaFragment.java`, `NarocilaFragment.java`, `MainActivity.java`, `RosKasaSoapClient.java`)**
  - Zapisati `wpiSessionId` v `PlaciloTp` ter ga serializirati/parsati v SOAP kot `A_VRSTA_STROSKA`.
  - Odstraniti prezgodnji `tapOnRecoverExecute()` iz `MainActivity.onCreate:50` (teče naj samo po prijavi in *po* osvežitvi `getAppConfig`).
  - Popraviti `bookConfirmedPOSTransaction`: pri celotnem plačilu nastaviti `FISKALIZACIJA = 1` in izvesti izpis (`STATUS = 2`).
  - Popraviti `NarocilaFragment:2404-2408` (ob `RESULT_CANCELED` shraniti recovery + `LAST_TRANSACTION`, ne brisati!).
  - Dodati blokado in 3-stopenjsko eskalacijo (`1`=opozorilo, `2`=auto check, `3`=reset) ob poskusu novega SixTap plačila.
- [x] **P0.5 Prekinitev plačila ob konfliktu verzije (`PlacilaFragment.java:702-713`, `NarocilaFragment.java:2218-2228`)**
  - Če `serverVer > currentRacun.getVerzijaZapisa()`, osvežiti račun, obvestiti uporabnika in **prekiniti** (ne klicati `onReady.run()` s starim saldom).
- [x] **P0.6 Blokada brisanja plačil (`PlacilaFragment.java:1374-1410, 1673-1745`)**
  - Strogo blokirati brisanje, če je `F_STEVILKA_RACUNA != 0` ali `STORNO_RACUN_ID != 0`.
  - Za lokalni račun (`RACUN_ID < 0`) fizično odstraniti plačilo namesto `rowDeleted = true`.
  - Pri brisanju plačila metode `3` (dobavnica/reprezentanca) samodejno izbrisati še pripadajoči Popust `99`.
- [x] **P0.7 Split računa — kritična popravka (`SplitRacunDialog.java`, `RacuniFragment.java`)**
  - Na zmanjšani poziciji originala nastaviti `STATUS = remaining.getKolicina()` in `_NeNarocaj = true` (prepreči storno kuhinjske listke).
  - Popraviti prenos decimalnih količin (celotna decimalna količina, nikoli `< 1.0 -> 1.0`).
  - Dodati vstopne zapore: `RACPLACI == 0`, brez `DN_ID`, `AKCIJA_ID`, `BKARTA_ID`, in `> 1` postavka ali količina `> 1`.

---

## Faza P1 — Cenovna, naročilna in davčna točnost + sistem pravic

- [x] **P1.1 Hitre tipke & `NACIN_PRODAJE` (`NarocilaFragment.java`, `QuickKeyAdapter.java`, `RosKasaSoapClient.java`)**
  - Prebrati `ENOTA_PRODAJE_ID` kot `ep` (ne fallback cena!), prikazati `NAZIV [EP]`, uporabiti `BARVA` in `DODATEK_ID`, popraviti prazno skupino (prikazati le `[ NAZAJ ]`, ne vseh tipk).
  - Povezati `NACIN_PRODAJE` `1, 2, 3, 4, 5, 6, 9, 10` iz cenika/tipk (odstraniti ugibanje po `"MALVAZIJA"`/`"WHISKY"`), uveljaviti cela števila količine pri `EP < 1`.
- [x] **P1.2 Formula `CenaPolnjenje` (`NarocilaFragment.java`, `RacpozicEditDialog.java`, `RosKasaSoapClient.java`)**
  - Prebrati `EM` (`ENOTA_MERE_ID`) v `CenikItem` in implementirati deljenje s `pompolnjenje` za `'D'` vs `'N'` (`EM = 1`).
- [x] **P1.3 Paketi `pompaket = 1` & cenovna pravila (`NarocilaFragment.java`, `CenikListAdapter.java`, `RosKasaSoapClient.java`, `VnosCeneDialog.java`)**
  - Parsati `TARIFA_ID` za sestavine v `CENIKVRVR`, uporabiti `CENA2` pri `CENA2AKTIVNA`, popraviti parsko izravnavo (upoštevati `comp.getKolicina()`) in neto prikaz paketa s popustom.
  - Popraviti `MODELCENA2` (ne prepisovati `cena1 = cena2` pri parsanju), dodati `STANDARD='SKRIJ'` (`NIMOZNAIZBIRA`), `DNCENIK` (`CENIKVRCENE`), try/catch za `parseInt(plu)` in validacije za ročni vnos cene `-1`/`-2` (`>0`, `VnosCeneZaVse`, `'KAVCIJA'`).
- [x] **P1.4 Naročila — `_NeNarocaj`, `IZVOR_STRM_ID`, združevanje, zaklepanje (`NarocilaFragment.java`, `RacpozicEditDialog.java`)**
  - Nastaviti `_NeNarocaj` po pravilih `PrintamNarocila` / `NePrintamNarT1..4` (ne trdo `true`); popraviti `IZVOR_STRM_ID` (`KUHINJA_ID` za `NIVO1_ID = 2`) in odstraniti trdo kodirana `natakarId=9999` / `cenikId=11246`.
  - Popraviti združevanje vrstic (`NOVAVRSTICANAROCILA`, `DODATNI_OPIS`, `HOD`, sinhronizacija `orderItems` in `racPozic`).
  - Razširiti `isRacunZaklenjen()` na `RACPLACI > 0`, `STORNO_RACUN_ID > 0`, `LOJALNOST_ID > 0`, `AKCIJA_ID > 0`, `UREJAMSTORNO == 0`, `BKARTA_ID`.
  - V `RacpozicEditDialog` zakleniti EP pri `STATUS > 0`, dodati `NiStornoIzpisanegaNarocila`, `MaxPopOseba` in omejitev opombe na 240 znakov.
- [x] **P1.5 `Popust 99` & avtomatski rabat partnerja (`Popust99Dialog.java`, `Globals.java`, `PlacilaFragment.java`, `NarocilaFragment.java`)**
  - Uveljaviti `MaxPopOseba`, blokado ob obstoječih plačilih/akcijah, neto izračun davka, `Popust99Proporcialno`, ter pri avtomatskem rabatu partnerja dodati zapis `PLACILO_ID = 99` v `racPlaci`.
- [x] **P1.6 Pravice & filtri seznamov (`RosKasaSoapClient.java`, `MizeFragment.java`, `RacuniFragment.java`, `StornoRazlogDialog.java`)**
  - V `getRacuniSeznam` poslati `OSEBA_ID` in `STRM_ID` glede na `SVidiVseRacune` / `SVidiVsaNarocila` / `OdpriVseRacune`.
  - Uveljaviti `IZPISANIVIDNIDO`, `SLahkoPonovnoIzpiseRacun`, `SLahkoBrisePozicijePlacil`, `OBRACUNDOVOLJEN`/`OBRACUNANDROID`/`OBRACUNBREZIZPISA` in `RAZLOGSTORNO` ter zaostriti `isPravicaCaptionMatch` (brez napačnega `contains` ujemanja).

---

## Faza P2 — Tiskanje, mize/rajoni, avtentikacija, predpomnilnik in plačilne kontrole

- [x] **P2.1 Tiskanje (`PrintDataHandler.java`, `RacunPrintBuilder.java`, `NarociloBlokPrintBuilder.java`, `RacuniFragment.java`)**
  - Popraviti `CopyNo` (`Kopija št.: 1` za 2. izpis, `zacStKopij + 1` pri ponovnem izpisu).
  - Popraviti bug `stornoRazlogId` (`PrintDataHandler:358`), dodati `ACQTRANSREF` deduplikacijo, `PRINTAMSLIPNARACUN`, storno `'REF'` slip iz `getSlipEma2(STORNO_RACUN_ID)`.
  - Ponovni izpis v `RacuniFragment` preusmeriti na `printReceiptComplete(...)`.
  - Naročilni blok ločiti po `IZPIS_NAROCILA` (`1` vs `>1`) namesto `kuhinjaId > 0`; odstraniti dvojni odrez papirja v `sendBytesToPrinter:500-501`.
- [x] **P2.2 Mize & rajoni (`MizeFragment.java`, `Globals.java`)**
  - Ohraniti dinamične markerje (`rajon < 0`) pri vseh rajonih, zapomniti izbran rajon v `Globals`, popraviti pogoj prikaza vrstice rajonov (`g.isRajoni() && rajoni.size() > 1`).
- [x] **P2.3 Avtentikacija, `MOBINI` & `LocalCacheManager` (`MainActivity.java`, `LoginFragment.java`, `Globals.java`, `LocalCacheManager.java`, `HotelSobeDialog.java`)**
  - Popraviti obrnjeno logiko `ANDROIDNFCIZKLOP` in upoštevati `NFCPRIJAVA`; v `HotelSobeDialog` preferirati **HEX** UID za NFC gosta.
  - Zavrniti PIN `'0'`, dodati 3-poskusno omejitev, `ODJAVA = 'D'` vidnost gumba in `setTekocaOseba(null)` v `NarocilaFragment:1442`.
  - Predelati `LocalCacheManager`: `getAppConfig` vedno svež ob zagonu, disk JSON cache za 6 velikih šifrantov (`Cenik`, `CenikVrVr`, `CenikVrCene`, `HitreTipke`, `NacPlac`, `Dodatki`, `StornoRazlogi`, `Lojalnost`) + gumb za ročno osvežitev (`btnRacprenos`) + veljavnost `CenikStDniObnova`.
  - Dodati `CheckPovezava`, varnostni timer `TIMERENABLEEKRAN`, Android *Back* potrditveni dialog + offline ponovno pošiljanje kronologije.
- [x] **P2.4 Plačilne kontrole & konkurenca (`PlacilaFragment.java`, `PartnerVnosDialog.java`, `PaytenPaymentService.java`, `SixTapPaymentService.java`, `RacunTp.java`)**
  - Ekskluzivnost Hotel kredita (brez delnih plačil/mešanja) in Dobavnice, omejitev 1 kartice na račun, Reprezentanca (`TIP_PARTNER = 5`) samo iz baze + `31`/`90`, splošni fiksni kupec (`NACPLAC.KUPEC_ID`).
  - Podpora za `VraciloNum`, `KavcijaNaRacunu`, `CENA0DOVOLJENA`/`HOTELKREDIT0`, case-insensitive JSON parser za SoftPOS, ohranitev `RACGLAVA.PLACANO` ob praznem `RACPLACI` ter `akcijaId` v `RacunTp.deepCopy()`.

---

## Faza P3 — Manjkajoči razširjeni moduli iz `RosKasaLight16` (po prioriteti strank)

- [x] **P3.1** Paketi `pompaket = 2` (Mix & Match izbirni dialog iz `CENIKVRVR`).
- [x] **P3.2** `FormNapitnine` (`NAPITNINAROS`), izpis napitnin pri inkasu (`getNapitninaSkupaj`, `NAPITNINEPROCENTKUH`) *(brez `CROFISK` po navodilu)*.
- [x] **P3.3** `ViesVatClient` (preverjanje EU DDV zavezancev v `PartnerVnosDialog`) in davčna skupina partnerja na izpisu.
- [x] **P3.4** `HotelSobeDialog`: Skupine (`getListaSkupin`, `HIS_CENIK_AI`, `CenikAiNaziv`) in Obroki (`getObroki`).
- [x] **P3.5** `SplitRacunDialog`: razširitev na do 5 ciljnih računov (`R1..R5`) in delitev paketov (`PAKET_DISTINCT`).
- [x] **P3.6** `FormCrm` (iskanje gosta, `crmInfo`, `gdprLog`, samodejni CRM popust, `crmGetStTock` / `crmTocke` in izpis točk).
- [x] **P3.7** Ostalo: `getReceptura`, `EDITKOLICINAADD`, seznam odprtih računov ob napaki obračuna, landscape `layout-land` postavitve, Star ESC/POS QR (tip 2) in Google Play demo bypass *(brez `FormNacPlacHit` po navodilu)*.
