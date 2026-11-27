package si.ros.RosKasa.print;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.models.AkcijaTp;
import si.ros.RosKasa.models.KartprijTp;
import si.ros.RosKasa.models.PartnerTp;
import si.ros.RosKasa.models.PlaciloTp;
import si.ros.RosKasa.models.RacunTp;
import si.ros.RosKasa.models.SlipEmaTp;
import si.ros.RosKasa.models.StornoRazlogTp;
import si.ros.RosKasa.soap.RosKasaSoapClient;

/**
 * Celovit asinhroni upravitelj tiskanja, skladen z Delphi uPrintData.pas.
 * Upravlja kaskado pridobivanja podatkov, pavze za odrez papirja ter tiskanje
 * računov, voucherjev, naročil in kuponov prek enotne Bluetooth seje.
 */
public class PrintDataHandler {

    private static final String TAG = "PrintDataHandler";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Glavna vstopna točka za celovito tiskanje računa in vseh dodatkov.
     */
    public static void printReceiptComplete(Context context, RacunTp racun, BluetoothPrintHelper.OnPrintListener listener) {
        if (listener != null) listener.onStart();

        executor.execute(() -> {
            BluetoothSocket socket = null;
            OutputStream outputStream = null;

            try {
                if (racun == null || racun.getRacunId() <= 0) {
                    postError(listener, "Neveljaven račun za tiskanje!");
                    return;
                }

                Globals globals = Globals.getInstance();
                AppPreferences prefs = new AppPreferences(context);
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                if (serverUrl == null || serverUrl.isEmpty()) serverUrl = globals.getServerUrl();
                if (token == null || token.isEmpty()) token = globals.getToken();

                // 1. KASKADA PRIDOBIVANJA PODATKOV (Pre-Print Retrieval) — VEDNO pred Bluetoothom,
                // da se RACIZPISAN vedno zapiše v bazo tudi ob napaki Bluetooth tiskalnika!
                globals.vpisiKronologijo("PrintDataHandler: Začetek priprave podatkov za R:" + racun.getRacunId());
                PrintDataWS printData = retrievePrintData(serverUrl, token, racun, globals);

                int zaPrintKopij = printData.getZaPrintStKopij();
                int zacStKopij = printData.getZacStKopij();
                int firstKopijaOznaka = (zacStKopij > 0) ? (zacStKopij + 1) : 0;
                RacunPrintBuilder.ReceiptResult firstRacResult = RacunPrintBuilder.buildReceipt(racun, globals, firstKopijaOznaka, printData);
                String textPreviewZaServer = firstRacResult.getTextPreview();

                // RACIZPISAN + posodobitev STKOPIJ (konStKopij = zacStKopij + zaPrintKopij) se MORA zapisati na strežnik neodvisno od stanja Bluetooth tiskalnika!
                int konStKopij = printData.getKonStKopij();
                BluetoothPrintHelper.sendRacIzpisanNaServer(context, racun, textPreviewZaServer, konStKopij);

                // 2. PREVERJANJE BLUETOOTH DOVOLJENJ (s prikazom sistemskega dialoga, če manjkajo)
                if (!BluetoothPrintHelper.hasBluetoothPermissions(context)) {
                    Activity act = findActivity(context);
                    if (act != null && !act.isFinishing() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        mainHandler.post(() -> ActivityCompat.requestPermissions(
                                act,
                                new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN},
                                1002
                        ));
                    }
                    postError(listener, "Aplikacija nima dovoljenja za Bluetooth! Potrdite dovoljenje za Bluetooth in ponovite izpis.");
                    return;
                }

                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    postError(listener, "Bluetooth vmesnik na napravi ni na voljo ali je izklopljen!");
                    return;
                }

                // 3. PRIPRAVA BLUETOOTH TISKALNIKA
                String targetPrinterName = globals.getPrinterRacuni();
                if (targetPrinterName == null || targetPrinterName.trim().isEmpty()) {
                    targetPrinterName = prefs.getPrinterRacuni();
                    if (targetPrinterName != null && !targetPrinterName.trim().isEmpty()) {
                        globals.setPrinterRacuni(targetPrinterName.trim());
                    }
                }
                if (targetPrinterName == null) targetPrinterName = "";
                targetPrinterName = targetPrinterName.trim();

                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (pairedDevices == null || pairedDevices.isEmpty()) {
                    postError(listener, "Ni najdenih seznanjenih Bluetooth naprav!");
                    return;
                }

                BluetoothDevice selectedDevice = null;
                if (!targetPrinterName.isEmpty()) {
                    for (BluetoothDevice device : pairedDevices) {
                        String devName = device.getName();
                        if (devName != null && (devName.equalsIgnoreCase(targetPrinterName) || devName.toLowerCase().contains(targetPrinterName.toLowerCase()))) {
                            selectedDevice = device;
                            break;
                        }
                    }
                }
                if (selectedDevice == null) {
                    selectedDevice = pairedDevices.iterator().next();
                }

                final String deviceName = selectedDevice.getName() != null ? selectedDevice.getName() : "POS Tiskalnik";
                bluetoothAdapter.cancelDiscovery();

                try {
                    socket = selectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                } catch (Exception e1) {
                    try {
                        java.lang.reflect.Method m = selectedDevice.getClass().getMethod("createRfcommSocket", int.class);
                        socket = (BluetoothSocket) m.invoke(selectedDevice, 1);
                        socket.connect();
                    } catch (Exception e2) {
                        throw e1;
                    }
                }
                outputStream = socket.getOutputStream();

                // 4. TISKANJE RAČUNA (ZANKA KOPIJ + DIALOG PAVZA ZA ODREZ PRED NASLEDNJO KOPIJO)
                for (int j = 0; j < zaPrintKopij; j++) {
                    RacunPrintBuilder.ReceiptResult racResult;
                    if (j == 0) {
                        racResult = firstRacResult;
                    } else {
                        int stKopijeOznaka = (zacStKopij > 0) ? (zacStKopij + j + 1) : j;
                        racResult = RacunPrintBuilder.buildReceipt(racun, globals, stKopijeOznaka, printData);
                    }

                    sendBytesToPrinter(outputStream, racResult.getPrintBytes());

                    // Pavza za odrez med posameznimi kopijami (razen po zadnji)
                    if (j < zaPrintKopij - 1) {
                        waitForUserCut(context, "Odrežite papir in pritisnite OK za tiskanje naslednje kopije!");
                    }
                }

                // 4. TISKANJE VOUCHERJA (če je omogočeno)
                boolean isStorno = (racun.getStornoRacunId() != null && racun.getStornoRacunId() > 0)
                        || (racun.getStornoOriginal() != null && racun.getStornoOriginal() > 0);

                if (globals.isPrintamVoucher() && !isStorno && (racun.getStatus() < 2)) {
                    RacunPrintBuilder.ReceiptResult voucherRes = VoucherPrintBuilder.buildVoucher(racun, globals);
                    if (voucherRes.getPrintBytes().length > 0) {
                        waitForUserCut(context, "Odrežite papir in pritisnite OK.");
                        sendBytesToPrinter(outputStream, voucherRes.getPrintBytes());
                    }
                }

                // 5. TISKANJE NAROČILA OB RAČUNU (če je omogočeno)
                if (globals.isPrintamNarocila() && !isStorno && (racun.getStatus() < 2)) {
                    List<RacunPrintBuilder.ReceiptResult> blokiNar = NarociloBlokPrintBuilder.buildNarociloBloki(racun, globals);
                    for (RacunPrintBuilder.ReceiptResult blok : blokiNar) {
                        waitForUserCut(context, "Odrežite papir in pritisnite OK.");
                        sendBytesToPrinter(outputStream, blok.getPrintBytes());
                    }
                }

                // 6. TISKANJE AKCIJ IN KUPONOV (če obstajajo)
                if (globals.isAkcije() && printData.isAkcijePrint() && !printData.getAkcijeList().isEmpty()) {
                    List<RacunPrintBuilder.ReceiptResult> kuponiRes = AkcijePrintBuilder.buildAkcijeKuponi(racun, printData.getAkcijeList(), globals);
                    for (RacunPrintBuilder.ReceiptResult kRes : kuponiRes) {
                        waitForUserCut(context, "Odrežite papir in pritisnite OK za nadaljevanje.");
                        sendBytesToPrinter(outputStream, kRes.getPrintBytes());
                    }
                }

                // Varno zaprtje tiskalniške seje
                try {
                    outputStream.flush();
                } catch (Exception ignored) {}

                // RACIZPISAN je bil že shranjen na strežnik pred Bluetooth tiskanjem
                postSuccess(listener, "Izpis uspešno poslan na " + deviceName + "!");

            } catch (SecurityException se) {
                Log.e(TAG, "Bluetooth SecurityException", se);
                postError(listener, "Dovoljenje za Bluetooth ni odobreno!");
            } catch (Exception e) {
                Log.e(TAG, "Napaka pri celovitem tiskanju", e);
                postError(listener, "Napaka tiskanja: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            } finally {
                Globals.getInstance().clearZadnjiSlip();
                try {
                    if (outputStream != null) outputStream.close();
                    if (socket != null) socket.close();
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Kaskadno asinhrono pridobivanje vseh manjkajočih podatkov pred tiskom (skladno z Delphi uPrintData.pas).
     */
    public static PrintDataWS retrievePrintData(String serverUrl, String token, RacunTp racun, Globals globals) {
        PrintDataWS data = new PrintDataWS();
        data.initData();
        data.setRacun(racun);
        data.setRacunId(racun.getRacunId());
        data.setZnesek(racun.getZnesek());
        data.setKasiral(racun.getKasiral() != null ? racun.getKasiral() : 0);
        data.setFPodpis(racun.getfPodpis() != null ? racun.getfPodpis().trim() : "");
        data.setFOznakaDu(racun.getfOznakaDu() != null ? racun.getfOznakaDu().trim() : "");

        int zacStk = racun.getStKopij() != null ? racun.getStKopij() : 0;
        int zaPrintStk = globals.stKopijPlacila(racun);
        data.setZacStKopij(zacStk);
        data.setZaPrintStKopij(zaPrintStk);
        data.setKonStKopij((zacStk == 0 && zaPrintStk > 1) ? zaPrintStk : zacStk + zaPrintStk);

        int stornoId = racun.getStornoRazlogId() != null ? racun.getStornoRazlogId() : 0;
        data.setStornoRazlogId(stornoId);

        // A. Partner
        int partnerId = racun.getPartnerId() != null ? racun.getPartnerId() : 0;
        if (racun.getRacPlaci() != null) {
            for (PlaciloTp pl : racun.getRacPlaci()) {
                if (pl != null && pl.getPartnerId() != null && pl.getPartnerId() > 0) {
                    partnerId = pl.getPartnerId();
                    if (pl.getNazivPartner() != null && !pl.getNazivPartner().trim().isEmpty()) {
                        data.setPartnerNaziv(pl.getNazivPartner().trim());
                        data.setPartnerNaslov(pl.getNaslovPartner() != null ? pl.getNaslovPartner().trim() : "");
                        data.setPartnerDavcna(pl.getDavcnaSt() != null ? pl.getDavcnaSt().trim() : "");
                        data.setStNarocilnice(pl.getStNarocilnice() != null ? pl.getStNarocilnice().trim() : "");
                    }
                    break;
                }
            }
        }
        data.setPartnerId(partnerId);
        // Vedno pokličemo getPartner za partnerId > 0, da pridobimo tudi podatke davčne skupine
        // (NAZIV_DAVCNASKUPINA, NAS_ULICA_DAVCNASKUPINA, DAVCNASTSKUPINA) — skladno z Delphi uPrintData.pas
        if (partnerId > 0 && serverUrl != null && !serverUrl.isEmpty()) {
            try {
                List<PartnerTp> pList = RosKasaSoapClient.getPartner(serverUrl, token, null, partnerId, null, null, false);
                if (pList != null && !pList.isEmpty()) {
                    PartnerTp p = pList.get(0);
                    if (data.getPartnerNaziv().isEmpty()) {
                        data.setPartnerNaziv(p.getNaziv());
                        String naslov = p.getNasUlica() + (!p.getNazivPosta().isEmpty() ? ", " + p.getNazivPosta() : "");
                        data.setPartnerNaslov(naslov);
                    }
                    String dsNaziv = p.getNazivDavcnaSkupina();
                    String dsNaslov = p.getNasUlicaDavcnaSkupina();
                    String dsDavcna = p.getDavcnaStSkupina();
                    if (dsDavcna.isEmpty() && dsNaziv.isEmpty()) {
                        if (data.getPartnerDavcna().isEmpty()) {
                            data.setPartnerDavcna(p.getDavcnaSt());
                        }
                        data.setPartnerDSNaziv("");
                        data.setPartnerDSNaslov("");
                        data.setPartnerDSDavcna("");
                    } else {
                        // Skladno z Delphi uPrintData.pas: če obstaja davčna skupina, se izpišeta oba partnerja,
                        // davčna številka pa pri davčni skupini.
                        data.setPartnerDavcna("");
                        data.setPartnerDSNaziv(dsNaziv);
                        data.setPartnerDSNaslov(dsNaslov);
                        data.setPartnerDSDavcna(dsDavcna);
                    }
                    data.setPartnerDavcnaSkupina(p.getDavcnaSkupina());
                }
            } catch (Exception e) {
                Log.w(TAG, "retrievePrintData napaka getPartner: " + e.getMessage());
            }
        }

        // B. Gost hotela (Prijava) ali Skupina (HIS_CENIK_AI)
        int prijavaId = 0;
        int hisCenikAi = 0;
        String hisCenikFallbackNaziv = "";
        if (racun.getRacPlaci() != null) {
            for (PlaciloTp pl : racun.getRacPlaci()) {
                if (pl != null && pl.getGostPrijavaId() != null && pl.getGostPrijavaId() > 0) {
                    prijavaId = pl.getGostPrijavaId();
                    break;
                }
                if (pl != null && pl.getHisCenikAi() != null && pl.getHisCenikAi() > 0) {
                    hisCenikAi = pl.getHisCenikAi();
                    if (pl.getNazivPartner() != null && !pl.getNazivPartner().trim().isEmpty()) {
                        hisCenikFallbackNaziv = pl.getNazivPartner().trim();
                    }
                    break;
                }
            }
        }
        data.setPrijavaId(prijavaId);
        data.setHisCenikAi(hisCenikAi);
        if (prijavaId > 0 && serverUrl != null && !serverUrl.isEmpty()) {
            try {
                KartprijTp kp = RosKasaSoapClient.getPrijava(serverUrl, token, prijavaId);
                if (kp != null && (kp.getProstorId() > 0 || !kp.getImeGosta().isEmpty())) {
                    String obrat = kp.getObratNaziv() != null ? kp.getObratNaziv() : "";
                    data.setPrijavaImeGosta("HK " + kp.getProstorId() + "/" + kp.getImeGosta() + (obrat.isEmpty() ? "" : "/" + obrat));
                }
            } catch (Exception e) {
                Log.w(TAG, "retrievePrintData napaka getPrijava: " + e.getMessage());
            }
        } else if (hisCenikAi > 0) {
            String skupNaziv = hisCenikFallbackNaziv;
            if (skupNaziv.isEmpty() && serverUrl != null && !serverUrl.isEmpty()) {
                try {
                    java.util.List<Integer> obratiList = new java.util.ArrayList<>();
                    if (globals.getHisObrat() > 0) obratiList.add(globals.getHisObrat());
                    List<RosKasaSoapClient.SkupinaItem> skupine = RosKasaSoapClient.getListaSkupin(serverUrl, token, obratiList);
                    if (skupine != null) {
                        for (RosKasaSoapClient.SkupinaItem s : skupine) {
                            if (s.cenikAi == hisCenikAi) {
                                skupNaziv = s.skupinaNaziv + (!s.storitevNaziv.isEmpty() ? " / " + s.storitevNaziv : "");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "retrievePrintData napaka getListaSkupin: " + e.getMessage());
                }
            }
            data.setHisCenikNaziv("Skupina: " + (!skupNaziv.isEmpty() ? skupNaziv : ("#" + hisCenikAi)));
        }

        // C. Storno razlog
        if (stornoId > 0) {
            List<StornoRazlogTp> razlogi = globals.getCachedStornoRazlogi();
            if (razlogi != null) {
                for (StornoRazlogTp sr : razlogi) {
                    if (sr != null && sr.getStornoRazlogId() == stornoId) {
                        data.setStornoRazlog(sr.getNaziv());
                        break;
                    }
                }
            }
        }

        // D. POS Slip (Ema2 / Worldline) — upošteva PRINTAMSLIPNARACUN, ACQTRANSREF deduplikacijo in storno 'REF' slip
        int stornoRacunIdForSlip = racun.getStornoRacunId() != null ? racun.getStornoRacunId() : 0;
        boolean hasCardPayment = false;
        if (racun.getRacPlaci() != null) {
            for (PlaciloTp pl : racun.getRacPlaci()) {
                if (pl != null) {
                    si.ros.RosKasa.models.NacPlacTp np = globals.getPlaciloById(pl.getPlaciloId());
                    String plNaz = (np != null && np.getNaziv() != null) ? np.getNaziv() : "";
                    int metoda = (np != null) ? np.getMetoda() : globals.placilometoda(pl.getPlaciloId());
                    if (metoda == 3 || metoda == 14 || pl.getPlaciloId() == 399 || pl.getPlaciloId() == 2
                            || (pl.getMRef() != null && !pl.getMRef().trim().isEmpty())
                            || plNaz.toUpperCase().contains("KART") || plNaz.toUpperCase().contains("POS")) {
                        hasCardPayment = true;
                        break;
                    }
                }
            }
        }
        if (globals.isPrintamSlipNaRacun() && (hasCardPayment || stornoRacunIdForSlip > 0) && serverUrl != null && !serverUrl.isEmpty()) {
            try {
                int targetSlipRacunId = (stornoRacunIdForSlip > 0) ? stornoRacunIdForSlip : racun.getRacunId();
                List<SlipEmaTp> slips = RosKasaSoapClient.getSlipEma2(serverUrl, token, targetSlipRacunId);
                if (slips != null && !slips.isEmpty()) {
                    StringBuilder slipSb = new StringBuilder();
                    java.util.Set<String> seenRefs = new java.util.HashSet<>();
                    for (SlipEmaTp s : slips) {
                        if (s == null) continue;
                        if (stornoRacunIdForSlip > 0 && !"REF".equalsIgnoreCase(s.getAvtorizacija())) {
                            continue;
                        }
                        String acqRef = s.getAcqTransRef() != null ? s.getAcqTransRef().trim() : "";
                        if (!acqRef.isEmpty() && !seenRefs.add(acqRef)) {
                            continue; // Deduplikacija po ACQTRANSREF
                        }
                        String sp = (s.getSlipPrint() != null && !s.getSlipPrint().trim().isEmpty())
                                ? s.getSlipPrint().trim()
                                : (s.getSlipPrints() != null ? s.getSlipPrints().trim() : "");
                        if (!sp.isEmpty()) {
                            slipSb.append("\n").append(sp);
                        }
                    }
                    if (slipSb.length() > 0) {
                        data.setSlipString(si.ros.RosKasa.payment.SixTapPaymentService.cleanSlipText(slipSb.toString()));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "retrievePrintData napaka getSlipEma2: " + e.getMessage());
            }
        }

        // Fallback: če iz strežnika nismo pridobili slipa, preveri lokalni zadnji prejeti POS slip
        if (globals.isPrintamSlipNaRacun() && hasCardPayment && data.getSlipString().isEmpty() && globals.getZadnjiSlipText() != null && !globals.getZadnjiSlipText().trim().isEmpty()) {
            if (globals.getZadnjiSlipRacunId() == 0 || globals.getZadnjiSlipRacunId() == racun.getRacunId()) {
                data.setSlipString(si.ros.RosKasa.payment.SixTapPaymentService.cleanSlipText(globals.getZadnjiSlipText()));
            }
        }

        // E. Akcije in kuponi (skladno z Delphi uPrintData.pas)
        boolean isAkcije = globals.isAkcije();
        int racId = racun.getRacunId();
        int racStatus = racun.getStatus();
        int stornoRacId = racun.getStornoRacunId() != null ? racun.getStornoRacunId() : 0;
        int stornoOrig = racun.getStornoOriginal() != null ? racun.getStornoOriginal() : 0;
        boolean hasServer = serverUrl != null && !serverUrl.trim().isEmpty();

        String diagAkcije = "retrievePrintData preverjanje akcij: racunId=" + racId
                + ", isAkcije=" + isAkcije
                + ", status=" + racStatus
                + ", stornoRacunId=" + stornoRacId
                + ", stornoOriginal=" + stornoOrig
                + ", hasServerUrl=" + hasServer;
        Log.d(TAG, diagAkcije);
        globals.vpisiKronologijoDebugL0(diagAkcije);

        if (!isAkcije) {
            String razlog = "Akcije preskocene: AKCIJE niso omogocene v nastavitvah (isAkcije=false).";
            Log.d(TAG, razlog);
            globals.vpisiKronologijoDebugL0(razlog);
        } else if (!hasServer) {
            String razlog = "Akcije preskocene: ni povezave s streznikom (serverUrl je prazen).";
            Log.w(TAG, razlog);
            globals.vpisiKronologijoDebugL0(razlog);
        } else if (racId <= 0) {
            String razlog = "Akcije preskocene: racunId <= 0 (" + racId + ").";
            Log.w(TAG, razlog);
            globals.vpisiKronologijoDebugL0(razlog);
        } else if (stornoRacId > 0 || stornoOrig > 0) {
            String razlog = "Akcije preskocene: racun je storno (stornoRacunId=" + stornoRacId + ", stornoOriginal=" + stornoOrig + ").";
            Log.d(TAG, razlog);
            globals.vpisiKronologijoDebugL0(razlog);
        } else if (racStatus != 1 && racStatus != 2) {
            String razlog = "Akcije preskocene: status racuna (" + racStatus + ") ni 1 ali 2.";
            Log.d(TAG, razlog);
            globals.vpisiKronologijoDebugL0(razlog);
        } else {
            try {
                // 1. Naziv akcije, če je akcija vezana na sam račun
                if (racun.getAkcijaId() != null && racun.getAkcijaId() > 0) {
                    AkcijaTp an = RosKasaSoapClient.akcijaNaziv(serverUrl, token, racun.getAkcijaId());
                    if (an != null) {
                        data.setAkcijaNaziv(an.getNaziv());
                        data.setAkcijaTipNaziv(an.getTipNaziv());
                    }
                }

                // 2. Klic akcijaSetKuponiRacuna: preveri artikle na računu in KREIRA kupone na strežniku
                List<AkcijaTp> kuponi = RosKasaSoapClient.akcijaSetKuponiRacuna(serverUrl, token, racId);

                // 3. Fallback na akcijaGet, če akcijaSetKuponiRacuna ni vrnila seznama neposredno
                if (kuponi == null || kuponi.isEmpty()) {
                    kuponi = RosKasaSoapClient.akcijaGet(serverUrl, token, racId);
                }

                // 4. Če imamo kupone, jih dodamo v podatke za tisk
                if (kuponi != null && !kuponi.isEmpty()) {
                    data.setAkcijeList(kuponi);
                    data.setAkcijePrint(true);
                    String kMsg = "Akcije uspesno pripravljene za tisk: " + kuponi.size() + " kuponov.";
                    Log.d(TAG, kMsg);
                    globals.vpisiKronologijoDebugL0(kMsg);
                } else {
                    String kMsg = "Za racunId=" + racId + " ni bilo generiranih promocijskih kuponov.";
                    Log.d(TAG, kMsg);
                    globals.vpisiKronologijoDebugL0(kMsg);
                }
            } catch (Exception e) {
                String errMsg = "retrievePrintData napaka akcije: " + e.getMessage();
                Log.w(TAG, errMsg, e);
                globals.vpisiKronologijoDebugL0(errMsg);
            }
        }

        // F. CRM točke in izpis (skladno z Delphi uPrintData.pas)
        String crmId = racun.getCrmId() != null ? racun.getCrmId().trim() : "";
        data.setCrmId(crmId);
        if (!crmId.isEmpty() && serverUrl != null && !serverUrl.trim().isEmpty()) {
            try {
                if (racun.getStatus() < 2 && racun.getRacunId() > 0) {
                    RosKasaSoapClient.crmTocke(serverUrl, token, racun.getRacunId());
                }
                RosKasaSoapClient.CrmInfoResult crmInfo = RosKasaSoapClient.crmInfo(
                        serverUrl, token, null, crmId);
                BigDecimal kapljice = (racun.getRacunId() > 0)
                        ? RosKasaSoapClient.crmGetStTock(serverUrl, token, racun.getRacunId())
                        : BigDecimal.ZERO;

                java.util.List<String> crmLines = new java.util.ArrayList<>();
                crmLines.add("CRM kartica: " + crmId);
                if (crmInfo != null && (crmInfo.fault == null || crmInfo.fault.trim().isEmpty())) {
                    String ime = (crmInfo.ime + " " + crmInfo.priimek).trim();
                    if (!ime.isEmpty()) crmLines.add("Gost: " + ime);
                    if (kapljice != null && kapljice.compareTo(BigDecimal.ZERO) != 0) {
                        crmLines.add(String.format(java.util.Locale.GERMAN, "Pridobljene točke: %.2f", kapljice));
                    }
                    if (crmInfo.stanje != null) {
                        crmLines.add(String.format(java.util.Locale.GERMAN, "Stanje točk: %.2f", crmInfo.stanje));
                    }
                } else if (kapljice != null && kapljice.compareTo(BigDecimal.ZERO) != 0) {
                    crmLines.add(String.format(java.util.Locale.GERMAN, "Pridobljene točke: %.2f", kapljice));
                }
                data.setCrmIzpisLines(crmLines);
            } catch (Exception e) {
                Log.w(TAG, "retrievePrintData napaka CRM: " + e.getMessage());
            }
        }

        return data;
    }

    /**
     * Varna pavza za odrez papirja med kopijami in dodatki (skladno z Delphi uPrintData.pas in FormKasaMobile.pas).
     * Upošteva TISKANJEPAVZA (modalni dialog za odrez z gumbom OK) in PRINTBLOKPAVZA (časovna pavza v ms ali s).
     */
    public static void waitForUserCut(Context context, String sporocilo) {
        Globals g = Globals.getInstance();

        // 1. Določitev časovne pavze iz MobIni PRINTBLOKPAVZA
        int rawPavza = g.getPrintBlokPavza();
        long casPavzeMs;
        if (rawPavza > 0) {
            // Če je vrednost <= 30, pomeni sekunde (npr. 2, 3, 4 s -> 2000, 3000, 4000 ms), sicer milisekunde (npr. 1000, 2000, 4000 ms)
            casPavzeMs = (rawPavza <= 30) ? (rawPavza * 1000L) : rawPavza;
        } else {
            // Privzeta varna pavza: zmanjšana na 1 sekundo (1000 ms)
            casPavzeMs = 1000L;
        }

        Activity act = findActivity(context);

        // 2. Če je vklopljena interaktivna pavza z dialogom (TISKANJEPAVZA = 'D' oz. true) in imamo aktiven Activity
        if (g.isTiskanjePavza() && act != null && !act.isFinishing()) {
            g.vpisiKronologijo("Tiskanje dialog pavza za odrez: " + sporocilo);
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                try {
                    new AlertDialog.Builder(act)
                            .setTitle("Tiskanje")
                            .setMessage(sporocilo != null ? sporocilo : "Odrežite papir in pritisnite OK za nadaljevanje.")
                            .setCancelable(false)
                            .setPositiveButton("OK", (dialog, which) -> {
                                dialog.dismiss();
                                latch.countDown();
                            })
                            .show();
                } catch (Exception e) {
                    latch.countDown();
                }
            });

            try {
                // Počakamo na uporabnikov pritisk na OK (največ 30 sekund)
                boolean ok = latch.await(30, TimeUnit.SECONDS);
                if (!ok) {
                    g.vpisiKronologijo("Tiskanje pavza: potekel 30s timeout za dialog");
                }
            } catch (InterruptedException ignored) {}
        } else {
            // 3. Avtomatska časovna pavza brez dialoga (PRINTBLOKPAVZA oz. 3000 ms)
            g.vpisiKronologijo("Tiskanje časovna pavza med kopijama: " + casPavzeMs + " ms");
            try {
                Thread.sleep(casPavzeMs);
            } catch (InterruptedException ignored) {}
        }
    }

    private static Activity findActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper) {
            return findActivity(((ContextWrapper) context).getBaseContext());
        }
        return null;
    }

    private static void sendBytesToPrinter(OutputStream outputStream, byte[] bytes) throws Exception {
        if (outputStream == null || bytes == null || bytes.length == 0) return;
        // Builderji že sami dodajo zaključek in odrez papirja (brez dvojnega GS V B 0)
        outputStream.write(bytes);
        outputStream.flush();
    }

    private static void postSuccess(BluetoothPrintHelper.OnPrintListener listener, String msg) {
        Globals.getInstance().vpisiKronologijo("PrintDataHandler USPEH: " + msg);
        mainHandler.post(() -> {
            if (listener != null) listener.onSuccess(msg);
        });
    }

    private static void postError(BluetoothPrintHelper.OnPrintListener listener, String errorMsg) {
        Globals.getInstance().vpisiNapakoKronologija("Tiskanje", errorMsg);
        mainHandler.post(() -> {
            if (listener != null) listener.onError(errorMsg);
        });
    }
}
