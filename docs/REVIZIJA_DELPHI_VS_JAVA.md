# Revizija in primerjava: `RosKasaLight16` (Delphi) vs. `C:\GeminiCli\RosKasa` (Java)

Datum revizije: **24. 11. 2026**
Referenčna specifikacija: [DELPHI_SPECIFIKACIJA_ROSKASALIGHT16.md](file:///C:/GeminiCli/RosKasa/docs/DELPHI_SPECIFIKACIJA_ROSKASALIGHT16.md)
Operativni TODO seznam: [TODO_FAZNI_PLAN.md](file:///C:/GeminiCli/RosKasa/docs/TODO_FAZNI_PLAN.md)

**Oznake statusov:**
* ✅ **Pravilno** — skladno z `RosKasaLight16` (vključno s ključnimi robnimi primeri)
* ⚠️ **Delno / z napako** — implementirano, vendar vsebuje hrošč, napačno formulo ali manjkajočo ključno kontrolo
* ❌ **Ni implementirano** — funkcionalnost iz `RosKasaLight16` manjka ali je neaktivna

---

## Povzetek stanja po 12 sklopih

| Sklop | Področje | Splošna ocena | Največja tveganja / manki |
|---|---|---|---|
| **1** | Zagon, `MOBINI`, predpomnilnik, UI | ⚠️ Delno | Obrnjen `ANDROIDNFCIZKLOP`; `LocalCacheManager` predpomni `MobileSetup` namesto 6 šifrantov; `getAppConfig` se ne osveži pred prijavo; manjka `CheckPovezava`, `TIMERENABLEEKRAN`, *Back* dialog |
| **2** | Avtentikacija (PIN/NFC), odjava, pravice | ⚠️ Delno | `MaxPopOseba` nikjer uporabljen; manjkajo pravice `SVidiVsaNarocila`, `SLahkoPonovnoIzpiseRacun`, `SLahkoBrisePozicijePlacil`, `SvezanaKnjigaRacunov`; PIN `'0'` in 3 poskusi; `ODJAVA='D'` |
| **3** | Mize, rajoni, markerji | ⚠️ Delno | Dinamični markerji izginejo pri filtru rajona; izbran rajon se ne zapomni; `getRacuniSeznam` ne pošilja `OSEBA_ID` in `STRM_ID` |
| **4** | Naročila, cenik, hitre tipke, cene, paketi | ⚠️ Z napakami | `ENOTA_PRODAJE` napačno kot cena; manjka `CenaPolnjenje`; hitre tipke ugibajo `NACIN_PRODAJE` po nazivu; paketi z napačnim davkom in brez `CENA2`/Mix&Match; `_NeNarocaj` vedno `true`; `DNCENIK` in `SKRIJ` manjkata |
| **5** | Urejanje pozicij, popusti, hodi, CRM | ⚠️ Delno | `isRacunZaklenjen()` ne zaklene ob delnem plačilu / stornu / lojalnosti / akciji / `UREJAMSTORNO=0`; `MaxPopOseba` in `NiStornoIzpisanegaNarocila` manjkata; `Popust 99` računa davek iz bruto; CRM manjka |
| **6** | Plačila, partnerji, sobe, fiskalizacija | ⚠️ Z napakami | Konflikt verzije ne prekine plačila; brisanje plačil ne blokira fiskaliziranih/storno računov; ni ekskluzivnosti HK/Dobavnice in 1-kartične omejitve; manjka VIES, `VraciloNum`, `Kavcija`, `CENA0`/`HK0`, Skupine/Obroki, `Napitnine`, HIT |
| **7** | SoftPOS (Payten & Worldline SixTap) | ⚠️ Kritične vrzeli | **Payten nima crash recovery** (`POS_CONFIRMED`, 60/180s, lock check); **SixTap ne zapiše/pošlje `WPI_SESSION_ID` v `A_VRSTA_STROSKA`**; `MainActivity.onCreate` prezgodaj porabi `tapOnRecoverPrijava`; manjka eskalacija ob novem plačilu |
| **8** | Seznam računov, predogled, Storno | ⚠️ Kritičen bug | **Storno računa `STATUS=4` napačno sproži SoftPOS refund** (manjka `status < 4`); manjka filter po osebi in `IZPISANIVIDNIDO`; ponovni izpis ne kliče `printReceiptComplete` |
| **9** | Delitev (Split) in Združevanje | ⚠️ Kritična buga | Split na zmanjšani poziciji ne nastavi `STATUS=KOLICINA`/`_NeNarocaj=true`; decimalna količina `<1.0` se pokvari v `1.0`; manjkajo vstopne zapore, paketi in `R1..R5`. Združevanje ✅ |
| **10** | Tiskanje, FURS QR, kopije, dodatki | ⚠️ Kritičen bug | **FURS QR uporablja `new Date()` namesto datuma/ure računa**; napačna `Kopija št.` (`2` namesto `1`); bug `stornoRazlogId` (v. 358); manjka storno `'REF'` slip iz `STORNO_RACUN_ID`, `ACQTRANSREF` dedup, Blok 1/2 po `IZPIS_NAROCILA` |
| **11** | Inkaso, Obračun, Kronologija | ⚠️ Delno | Inkaso ✅ (manjka `getNapitninaSkupaj`); Obračun ne preverja `OBRACUNDOVOLJEN`/`OBRACUNANDROID`/`OBRACUNBREZIZPISA` in ne prikaže odprtih računov ob napaki; kronologija nima offline ponovnega pošiljanja |
| **12** | SOAP konkurenca & `__OriginalObject` | ⚠️ Delno | Glava in plačila ✅ (`__OriginalObject`), pozicije namerno brez `__OriginalObject`; `VERZIJA_ZAPISA` in `'race'` ✅; `PLACANO` se ob praznem `RACPLACI` ne ohrani |

---

## Podrobna primerjalna matrika (Sklopi 1–12)

### Sklop 1: Zagon, registracija, `MOBINI`, lokalni predpomnilnik in UI

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Prvi zagon (`getOsebaToken` + `aktivirajMobile`) | ⚠️ | [LoginFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LoginFragment.java#L149-L214), [AppPreferences.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/AppPreferences.java#L67-L69) | Napaka `aktivirajMobile` in `getAppConfig` se tiho ignorira (`Log.w`); `naziv` se shrani kot `""`; ne zahteva ponovnega zagona. |
| Google Play demo bypass (`DemoGooglePlay` / `demo`) | ❌ | — | Ni implementirano. |
| Preverjanje omrežja (`CheckPovezava`) | ❌ | [AndroidManifest.xml](file:///C:/GeminiCli/RosKasa/app/src/main/AndroidManifest.xml#L6) | Dovoljenje je v manifestu, `ConnectivityManager` ni nikjer uporabljen. |
| Osvežitev `getAppConfig` ob vsakem zagonu | ⚠️ | [MainActivity.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/MainActivity.java#L36), [LoginFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LoginFragment.java#L68) | Ker `MainActivity` najprej naloži `sifranti_setup.json`, je `cachedOsebje` poln in se `getAppConfig` pred prijavo sploh ne izvede. |
| Ločitev `F_POS_ID` vs `TIPKE_POS_ID` | ✅ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L617-L622), [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L3362-L3366) | Dosledno ločeno (manjša opomba: [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L963) meša `mobileId` in `fPosId`). |
| Korekcija `STEVILOZNAKOV = 48 -> 42` | ✅ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L681-L683) | Pravilno implementirano. |
| 3-stopenjsko plastenje `MOBINI0` → `MOBINI` → `MOBINI2` | ✅ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L603-L614), [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L708-L758) | Case-insensitive, zadnja vrednost zmaga. |
| `ANDROIDNFCIZKLOP` in `LOGOUTCAS` v `MOBINI` | ❌ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L1020-L1023), [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L625-L633) | `ANDROIDNFCIZKLOP='D'` napačno **vklopi** NFC in ga `loadFromMobileSetup` kasneje prepiše; `LOGOUTCAS` množi s `6000` in ni uporabljen. |
| `LocalCacheManager` (`CenikLokalno`, `CenikStDniObnova`) | ❌ | [LocalCacheManager.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/cache/LocalCacheManager.java#L29-L144) | Obrnjeno: shranjuje `MobileSetup` namesto 6 večjih šifrantov; potekel cache vseeno vrne `true`; ni gumba za ročno prisilno osvežitev (`btnRacprenos`). |
| Orientacija portrait/landscape & `DisableEkran` + timer | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L410-L451), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L2715-L2736) | Ni `layout-land` preureditve; `MizeFragment`/`RacuniFragment` nimata `disableEkran`; **manjka varnostni timer `TIMERENABLEEKRAN`**. |
| Android gumb *Back* (potrditev izhoda + kronologija) | ❌ | [MainActivity.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/MainActivity.java) | Ni `OnBackPressedCallback` — takojšen izhod brez potrditve in kronologije. |

### Sklop 2: Avtentikacija (PIN/NFC), odjava in pravice

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| PIN prijava (`NajdiOseboZaPin`, potek `CHECKPINPOTEKEL`, `SLahkoDelaSKaso`) | ⚠️ | [LoginFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LoginFragment.java#L264-L297), [OsebaTp.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/models/OsebaTp.java#L171-L178) | Potek PIN-a in `SLahkoDelaSKaso` ✅; **ne zavrne PIN `'0'`** in **nima omejitve 3 poskusov**. |
| NFC prijava (HEX + dec proti `KARTICA_ID` / `KARTICA2_ID`) | ⚠️ | [NfcHelper.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/nfc/NfcHelper.java#L55-L81), [MainActivity.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/MainActivity.java#L56-L112) | Dekodiranje in ujemanje ✅; `MainActivity` in `LoginFragment` ne upoštevata `NFCPRIJAVA` / `ANDROIDNFCIZKLOP` in nimata `PrvaPinPrijava` zaščite. |
| Akcije po prijavi (`TapOnRecover`, `Cena2VikendPrazniki`, kronologija) | ⚠️ | [LoginFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LoginFragment.java#L309-L384) | `Cena2VikendPrazniki` ✅; `TapOnRecover` teče vzporedno z `getAppConfig` (race condition); lokalna kronologija se ne pošilja ob prijavi. |
| Odjava (`ODJAVA = 'D'`, kronologija, čiščenje seje) | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L1437-L1457), [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L80-L85) | Gumb vedno viden (ignorira `pLogout`); ne pošilja kronologije; **bug v `NarocilaFragment:1442`**: pri *"Shrani in odjavi"* ne pokliče `setTekocaOseba(null)`. |
| `OsebiDovoljeno` (1-based) in `MaxPopOseba` | ⚠️ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L2240-L2294) | Indeksiranje ✅ (opozorilo: `contains` v `isPravicaCaptionMatch` lahko povzroči lažna ujemanja); **`getMaxPopOseba()` ni nikjer klican**. |
| Uveljavljenost pravic v UI | ⚠️ | [PraviceConsts.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/models/PraviceConsts.java) | Uveljavljenih 8 pravic; **manjkajo:** `SVidiVsaNarocila`, `SLahkoPonovnoIzpiseRacun`, `SLahkoBrisePozicijePlacil`, `SvezanaKnjigaRacunov`. |

### Sklop 3: Mize, rajoni in markerji

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Mize iz `MOBILE_SETUP_MIZE` po `ZAP` & tap na mizo | ✅ | [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L183-L193), [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L308-L317) | Pravilno sortirano in odpiranje prostih/zasedenih miz deluje. |
| Rajoni (`RAJONI`, `RAJONDEFAULT`, pomnjenje izbire) | ⚠️ | [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L104-L115) | Napačen pogoj prikaza (`rajoni.isEmpty() && !g.isRajoni()`); izbran rajon se ne shrani v `Globals` in se ob vrnitvi na Mize ponastavi. |
| Dinamični markerji (`rajon < 0`) pri filtru rajona | ❌ | [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L384) | `if (selectedRajon == 0)` skrije vse ročne markerje ob izbiri kateregakoli rajona ali `RAJONDEFAULT > 0`! |
| `getRacuniSeznam` filter po osebi in `TekociStrm` | ❌ | [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L1008-L1020), [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L225-L231) | Pošlje samo `OD_DATUM` in `STATUS` (brez `OSEBA_ID` in `STRM_ID`); ne upošteva `SVidiVsaNarocila` / `OdpriVseRacune`. |
| Samodejno shranjevanje neshranjenega naročila, prosti marker & zamenjava markerja | ✅ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L1626-L1634), [MizeFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/MizeFragment.java#L426-L455), [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L1184-L1243) | Pravilno implementirano (manjša opomba: zamenjava markerja ne posodobi lokalnega `activeMarker`, če gre za tekoči račun). |

### Sklop 4: Naročila, cenik, hitre tipke, cene in paketi

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Hitre tipke: drill-down, `ENOTA_PRODAJE_ID`, `BARVA`, `DODATEK_ID`, prazna skupina | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L883), [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L1279-L1332), [QuickKeyAdapter.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/QuickKeyAdapter.java#L95-L104) | **Hrošči:** `ENOTA_PRODAJE` se uporabi kot fallback cena (namesto `ep` in prikaza `NAZIV [EP]`); prazna skupina naloži vse tipke; `BARVA` in `DODATEK_ID` se ne uporabljata. |
| Cenik iskanje, `CENA0*`, `STANDARD='SKRIJ'` | ⚠️ | [CenikListAdapter.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/CenikListAdapter.java#L75-L132), [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L723-L731) | Iskanje in `CENA0*` ✅; manjka `STANDARD='SKRIJ'` (`NIMOZNAIZBIRA`); `parseInt(plu)` brez try/catch. |
| `MODELCENA2`, vikend/prazniki, `DNCENIK` | ⚠️ | [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L650-L685), [CenikListAdapter.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/CenikListAdapter.java#L53-L70) | Vikend/prazniki ✅; pri parsanju `CENA1==0` se napačno prepiše s `CENA2`; **`DNCENIK` (`tblCenikVrCene`) cenovno ni implementiran**. |
| Ročni vnos cene (`-1`, `-2`) | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L850-L901), [VnosCeneDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/VnosCeneDialog.java#L114-L124) | Manjka validacija `> 0` za `-1`, `VnosCeneZaVse`/`VidiVsaNarocila`, `paket != 2` in izjema `'KAVCIJA'`. |
| `NACIN_PRODAJE`, lestvice EP in `CenaPolnjenje` | ❌ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L865-L908), [LestvicaPolnjenjaDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LestvicaPolnjenjaDialog.java#L58-L90) | Cenik odpira lestvico le za `1, 2` (ne `3,4,9,10`); hitre tipke ugibajo po besedah (`MALVAZIJA`, `WHISKY`); ni `NACIN_PRODAJE` 5/6 niti celih števil pri `EP<1`; **formula `CenaPolnjenje` popolnoma manjka**. |
| Paketi (`pompaket = 1` in `pompaket = 2` Mix & Match) | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L987-L1102), [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L716-L771) | `pompaket=1`: komponente ne berejo svojega `TARIFA_ID` niti `CENA2`, parska izravnava ignorira `comp.getKolicina()`. **`pompaket=2` (Mix & Match) ni implementiran.** |
| Združevanje vrstic, `IZVOR_STRM_ID`, `LOKATOR`, `_NeNarocaj` | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L956-L966), [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L1104-L1175) | `LOKATOR` ✅; združevanje ignorira `NOVAVRSTICANAROCILA`/`DODATNI_OPIS`/`HOD` in desinhronizira `orderItems`; `IZVOR_STRM_ID` zamenjan z `IZVOR_PRIHODEK_ID`; trdo `natakarId=9999`/`cenikId=11246`; **`_NeNarocaj` je vedno `true`**. |

### Sklop 5: Urejanje pozicij, popusti, hodi in CRM

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| `RacpozicEditDialog` (količina, EP, popust, dodatki, receptura, soft-delete) | ⚠️ | [RacpozicEditDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacpozicEditDialog.java#L122-L306) | Soft-delete ✅, dodatki ✅; **manjkajo:** `EDITKOLICINAADD`, zaklep EP pri `STATUS > 0`, omejitev `MaxPopOseba`, omejitev opombe na 240 znakov, `getReceptura` in `NiStornoIzpisanegaNarocila`. |
| Zaklepanje urejanja računa (`isRacunZaklenjen`) | ⚠️ | [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L401-L408) | Zaklene le `status 2/4` in `isPlacan()`. **Ne zaklene** ob delnem plačilu (`RACPLACI > 0`), `STORNO_RACUN_ID > 0`, `LOJALNOST_ID > 0`, `AKCIJA_ID > 0`, `UREJAMSTORNO == 0` ali `BKARTA_ID`. |
| `Popust 99` (`PreveriPopustNaRacunu`, %/€, porazdelitev, brisanje) | ⚠️ | [Popust99Dialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/Popust99Dialog.java#L52-L238), [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L2042-L2107) | %/€, `DELNI_ZNESEK=0` in `brisiPopustNaRacun` ✅; **napake:** manjka `MaxPopOseba`, ne blokira ob obstoječih plačilih/akcijah, davek se računa iz bruto namesto neto, ni `Popust99Proporcialno`. |
| Lojalnost (`getLojalnostna`, `setLojalnost`) | ✅ | [LojalnostPopustDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/LojalnostPopustDialog.java), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L1747-L1828) | Pravilno (manjša opomba: preveriti `racunId > 0` pred klicem). |
| Hodi (`HodiDialog`, `TekociHod`, `natisniHod`) | ✅ | [HodiDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/HodiDialog.java#L191-L288) | Pravilno (opombi: 10 namesto 8 gumbov; pri paketih posodobi le eno komponento). |
| CRM (`FormCrm`, `crmInfo`, `gdprLog`, točke) | ❌ | — | Ni implementirano (obstaja le polje `crmId`). |

### Sklop 6: Plačila, partnerji, hotel sobe, DN, napitnine in fiskalizacija

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Delna plačila, preplačilo, `FiskalnoEnako` | ✅ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L425-L583) | Pravilno implementirano. |
| `VraciloNum`, `KavcijaNaRacunu`, `CENA0DOVOLJENA`/`HOTELKREDIT0` | ❌ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L275-L548) | Zastavice v `Globals` niso nikjer uporabljene; vračilo gotovine, negativni zneski in znesek 0 so blokirani. |
| Ekskluzivnost HK & Dobavnice, omejitev 1 kartice | ❌ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L752-L788), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L2277-L2322) | HK dovoli celo delno plačilo; ni omejitve mešanja niti omejitve 1 kartičnega plačila na račun. |
| Pred-plačilni `getVerzijaOfRacglava` | ⚠️ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L702-L735), [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L2218-L2228) | **KRITIČEN BUG:** ob zaznani novejši verziji na strežniku osveži račun, a **ne prekine plačila** — takoj izvede `onReady.run()` s starim saldom! |
| `PartnerVnosDialog` (iskanje, Reprezentanca, VIES, rabat, fiksni kupec) | ⚠️ | [PartnerVnosDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PartnerVnosDialog.java#L104-L226), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L242-L257) | `GetPartner` in `STNAROC` ✅; **napake:** Reprezentanca (`5`) dovoli ročni vnos, manjkata `31`/`90`, **manjka VIES**, avtomatski rabat ne doda zapisa `PLACILO_ID = 99` v `racPlaci`, fiksni kupec le za `KKARTICA_KUPECID_ROCNO`. |
| `HotelSobeDialog` (obrati, `Blokada HK`, NFC, Skupine, Obroki) | ⚠️ | [HotelSobeDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/HotelSobeDialog.java#L70-L306) | Obrati + `Blokada HK` (`'S'`) + `'X'` ✅; NFC na `229-231` preferira decimalno namesto **HEX**; **manjkajo Skupine (`getListaSkupin`, `hisCenikAi`) in Obroki (`getObroki`)**. |
| `DelovniNalogiDialog` & `OpombaRacunaDialog` | ✅ | [DelovniNalogiDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/DelovniNalogiDialog.java), [OpombaRacunaDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/OpombaRacunaDialog.java) | Pravilno implementirano. |
| `FormNapitnine` (`NAPITNINAROS`), CRO napojnice, `FormNacPlacHit` | ❌ | — | Niso implementirani (POS `tipAmount` se parsa, a brez preverjanja `isNapitninaPos()`). |
| Fiskalizacija in brisanje plačil | ⚠️ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L1374-L1745), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L2619) | SoftPOS `void`/`REFUND` pred brisanjem ✅; **KRITIČNO:** brisanje ne blokira fiskaliziranih (`F_STEVILKA_RACUNA != 0`) niti storno računov, ne briše Popusta 99 pri metodi 3, in uporabi `rowDeleted` tudi pri `RACUN_ID < 0`. |

### Sklop 7: SoftPOS integracije (Payten & Worldline SixTap)

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| **Payten:** prodaja, `Round(amt*100)`, `void` (`DELNI_ZNESEK+NAPITNINA`), `setSlipEma` | ✅ | [PaytenPaymentService.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/payment/PaytenPaymentService.java#L47-L91), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L1596-L1637) | Osnovni tok in `ActivityResultLauncher` izolacija delujeta pravilno. |
| **Payten:** 5-stopenjski stanjski avtomat, `POS_CONFIRMED` recovery, 60s/180s cooldown, `isPayTenIntransaction` guard | ❌ | [PaytenPaymentService.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/payment/PaytenPaymentService.java#L61-L63), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L2289-L2348) | **KRITIČNO:** Celoten Payten crash recovery in zaščita pred podvojenim bremenjenjem manjkata! |
| **SixTap:** WPI operacije, `Round(amt*100)`, `setSlipEma`, Test Print reset | ✅ | [SixTapPaymentService.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/payment/SixTapPaymentService.java#L23-L114), [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L196-L197) | Osnovne WPI operacije, `CANCEL_PAYMENT` vs `REFUND` in reset delujejo. |
| **SixTap:** GUID `WPI_SESSION_ID` v `RACPLACI.A_VRSTA_STROSKA` | ❌ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L2554-L2579), [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L3655-L3753) | **KRITIČNO:** `wpiSessionId` se ne zapiše v `PlaciloTp`, ne serializira v SOAP `A_VRSTA_STROSKA` in se bere iz napačnega taga (`WPI_SESSION_ID`). |
| **SixTap:** `ResultCode != RESULT_OK`, 3-stopenjska eskalacija, `TapOnRecoverExecute` | ⚠️ | [MainActivity.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/MainActivity.java#L50), [MainActivity.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/MainActivity.java#L283-L398), [NarocilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/NarocilaFragment.java#L2404-L2408) | **KRITIČNO:** 1) `MainActivity.onCreate` prezgodaj porabi `tapOnRecoverPrijava=true`; 2) `bookConfirmedPOSTransaction` ne nastavi `STATUS=2` in `FISKALIZACIJA=1`; 3) `NarocilaFragment` ob `RESULT_CANCELED` takoj pobriše recovery; 4) pri novem plačilu ni blokade/3-stopenjske eskalacije. |
| Case-insensitive JSON parsing za SoftPOS | ❌ | [SixTapPaymentService.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/payment/SixTapPaymentService.java#L148-L208), [PaytenPaymentService.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/payment/PaytenPaymentService.java#L120-L155) | Uporablja case-sensitive `JSONObject.optString`. |

### Sklop 8: Seznam računov, predogled in Storno

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Preklop 1↔2/4, predogled, `SLahkoStorniraRacun`, samodejni izpis `data1` | ✅ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L125-L572), [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L863-L984) | Osnovni tok seznama, predogleda in tiska storno računa deluje pravilno. |
| Filter po osebi & `IZPISANIVIDNIDO` | ❌ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L259-L354) | Vsak natakar vidi vse račune; `IZPISANIVIDNIDO` neuporabljen (trdo `-3` dni). |
| Ponovni izpis kopije (`SLahkoPonovnoIzpiseRacun`, `printReceiptComplete`) | ⚠️ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L467-L527) | Pravica ni preverjena; kliče `printReceipt` namesto `printReceiptComplete` (manjkajo POS slip, partner, prijava, storno razlog). |
| `SpreprecimEditStornoPozicij` & `RAZLOGSTORNO` | ⚠️ | [StornoRazlogDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/StornoRazlogDialog.java#L40-L92), [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L556-L559) | `UREJAMSTORNO=0` se pošlje in prebere, vendar ga `NarocilaFragment` ne zaklene; `isRazlogStorno()` ni uporabljen. |
| SoftPOS storno guard `1 < STATUS < 4` | ❌ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L574-L816) | **KRITIČEN BUG:** manjka `status < 4` — pri stornu obračunanega računa (`STATUS=4`) napačno sproži POS `void`/`REFUND`! |

### Sklop 9: Delitev (Split) in Združevanje računov

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Split – vstopne zapore, `R1..R5`, paketi | ⚠️ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L1150-L1160), [SplitRacunDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/SplitRacunDialog.java#L42-L186) | Manjkajo zapore (`RACPLACI`, `DN_ID`, `AKCIJA_ID`, `BKARTA_ID`, `>1` postavka/količina); podpira le 2 računa (ne `R1..R5`); brez paketov (`PAKET_DISTINCT`). |
| Split – decimalne količine & `STATUS=KOLICINA` / `_NeNarocaj` | ❌ | [SplitRacunDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/SplitRacunDialog.java#L106-L172), [SplitRacunDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/SplitRacunDialog.java#L214-L220) | **KRITIČNA BUGA:** 1) količina `< 1.0` se pri prenosu poveča v `1.0`! 2) Zmanjšana pozicija na originalu nima `STATUS = KOLICINA` in `_NeNarocaj = true` (tveganje storno kuhinjskih listkov). |
| Združevanje računov (`>=2`, marker max 19, `zdruziRacune`) | ✅ | [ZdruziRacuneDialog.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/ZdruziRacuneDialog.java#L59-L123) | Pravilno implementirano. |

### Sklop 10: Tiskalniški podsistem, FURS QR, kopije in dodatni izpisi

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Kaskada `PrintDataHandler` (`akcija*`, `getSlipEma2`, `getPrijava`, `GetPartner`, `StornoRazlog`) | ⚠️ | [PrintDataHandler.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/PrintDataHandler.java#L237-L427) | **Bug v. 358:** `stornoRacId` bere `getStornoRazlogId()` namesto `getStornoRacunId()`. Manjkajo: `ACQTRANSREF` dedup, `PRINTAMSLIPNARACUN`, storno `'REF'` slip iz `getSlipEma2(STORNO_RACUN_ID)`, `CenikAiNaziv`, davčna skupina, CRM. |
| Številčenje kopij (`Kopija št. X`) | ⚠️ | [PrintDataHandler.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/PrintDataHandler.java#L136-L146) | **Bug:** pri prvem izpisu z 2 kopijama izpiše `Kopija št.: 2` namesto `1`; pri ponovnem izpisu izpiše `zacStKopij` namesto `zacStKopij + 1`. |
| Pavza za odrez, enotna BT seja, OptiPos UTF-8, `insertIzpisan` (`CAS_IZPISA`), Test Print | ✅/⚠️ | [PrintDataHandler.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/PrintDataHandler.java#L436-L503), [BluetoothPrintHelper.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/BluetoothPrintHelper.java#L76-L207) | Vse deluje pravilno; manjša napaka: dvojni `escCut` / `GS V B 0` v `sendBytesToPrinter:500-501`. |
| Dodatki: Voucher, Akcije QR, Naročilni blok | ⚠️ | [NarociloBlokPrintBuilder.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/NarociloBlokPrintBuilder.java#L36) | Voucher & Akcije QR ✅; Naročilni blok loči Blok 1/2 po `kuhinjaId > 0` (skoraj vedno `512600`!) namesto po `IZPIS_NAROCILA`. |
| FURS QR (SLO Mod10, HR `CROFISK`, Star ESC) | ⚠️ | [RacunPrintBuilder.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/RacunPrintBuilder.java#L529), [FursQrHelper.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/print/FursQrHelper.java) | **KRITIČEN BUG (`RacunPrintBuilder:529`):** poda `new Date()` namesto `racun.getDatum()` + `racun.getUra()`! HR `CROFISK` URL in Star ESC/POS QR (tip 2) nista implementirana. |

### Sklop 11: Inkaso, Obračun in Kronologija

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| Inkaso (`getReport('Zakljucni1')` + pravici) | ✅ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L1046-L1090) | Pravilno implementirano. |
| Inkaso napitnine (`getNapitninaSkupaj`, `NAPITNINEPROCENTKUH`) | ❌ | — | Ni implementirano. |
| Obračun (`narediObracun`) | ⚠️ | [RacuniFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/RacuniFragment.java#L1092-L1144) | `SLahkoPozeneObracun` ✅; ne preverja `OBRACUNDOVOLJEN`, `OBRACUNANDROID`, `OBRACUNBREZIZPISA`; trdo `"ESC/POS"` namesto `getReportFormat()`; ob napaki ne prikaže seznama odprtih računov. |
| Kronologija (`MAID:`, 950 znakov, offline ponovno pošiljanje) | ⚠️ | [Globals.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/Globals.java#L480-L562) | Prefiks in 950-znakovno krajšanje ✅; manjka branje `kronologija.log` in ponovno pošiljanje neuspelih zapisov na strežnik. |

### Sklop 12: SOAP konkurenca (`VERZIJA_ZAPISA`) in `__OriginalObject`

| Funkcionalnost | Status | Java datoteka & vrstice | Ugotovitev |
|---|---|---|---|
| `__OriginalObject` (glava, pozicije, plačila) | ⚠️ | [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L3479-L3751), [RacunTp.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/models/RacunTp.java#L61-L120) | Glava & plačila ✅; pozicije namerno brez `__OriginalObject` (zaradi `"race racpozic"`); `RacunTp.deepCopy()` ne kopira `akcijaId`. |
| `VERZIJA_ZAPISA`, `VersionConflictException`, `'race'` zaščita | ✅ | [RosKasaSoapClient.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/soap/RosKasaSoapClient.java#L3099-L3281), [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L400-L421) | Detekcija in zaščita pred brisanjem plačila delujeta (razen buga v `zagotoviRacunNaStrezniku`, glej Sklop 6). |
| Ohranjanje `RACGLAVA.PLACANO` ob praznem odzivu | ⚠️ | [PlacilaFragment.java](file:///C:/GeminiCli/RosKasa/app/src/main/java/si/ros/RosKasa/ui/PlacilaFragment.java#L601-L623) | Če strežnik vrne prazen `RACPLACI` in `PLACANO = 0`, `preracunajPlacano()` prepiše na `0`. |
