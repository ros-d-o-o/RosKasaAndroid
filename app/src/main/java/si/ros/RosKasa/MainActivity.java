package si.ros.RosKasa;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import java.math.BigDecimal;

import si.ros.RosKasa.soap.RosKasaSoapClient;
import si.ros.RosKasa.ui.LoginFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Zabeleži začetek zagona aplikacije in verzijo APK (še pred povezavo na web service)
        Globals g = Globals.getInstance();
        g.initContext(getApplicationContext());
        g.markAppStartupBegin(this);

        setContentView(R.layout.activity_main);

        // Android 15 / 16 (Pixel 9, targetSdk 36) edge-to-edge zaščita:
        // vsebina ne sme segati pod statusno vrstico (ura, wifi, kamera) ali navigacijsko vrstico
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(false);
            insetsController.setAppearanceLightNavigationBars(false);
        }
        View rootContainer = findViewById(R.id.fragmentContainer);
        if (rootContainer != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootContainer, (v, windowInsets) -> {
                Insets systemBars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                int bottom = Math.max(systemBars.bottom, ime.bottom);
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottom);
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(rootContainer);
        }

        AppPreferences prefs = new AppPreferences(this);
        g.setServerUrl(prefs.getServerUrl());
        g.setToken(prefs.getToken());
        g.setNazivMobile(prefs.getNaziv());
        g.setTipkePosId(prefs.getTipkePosId());
        g.setfPosId(prefs.getfPosId());
        g.setfPoslovniProstorId(prefs.getfPoslovniProstorId());
        g.setTocilnicaId(prefs.getTocilnicaId());
        g.setKuhinjaId(prefs.getKuhinjaId());
        g.setHisObrat(prefs.getHisObrat());
        g.setPrinterRacuni(prefs.getPrinterRacuni());
        try { g.setMobileId(Integer.parseInt(prefs.getMobileId())); } catch (Exception ignored) {}
        prefs.loadSavedPrinterSetup(g);
        prefs.loadSavedSifranti();

        initNfc();
        initPaymentLaunchers();
        requestBluetoothPermissionsIfNeeded();

        // Nastavitev napak za SOAP klice v debug načinu
        RosKasaSoapClient.setErrorListener((method, errorMessage) -> {
            Toast.makeText(MainActivity.this, "[SOAP DEBUG NAPAKA - " + method + "]: " + errorMessage, Toast.LENGTH_LONG).show();
        });

        if (savedInstanceState == null) {
            navigateToFragment(new LoginFragment());
        }
        lastOrientation = getResources().getConfiguration().orientation;

        // Android gumb Back: potrditveni dialog za izhod + pošiljanje kronologije (skladno z Delphi)
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Izhod iz aplikacije")
                        .setMessage("Ali res želite zapreti aplikacijo RosKasa?")
                        .setPositiveButton("Da", (d, w) -> {
                            Globals.getInstance().vpisiKronologijo("Izhod iz aplikacije (Back)");
                            Globals.getInstance().posljiOfflineKronologijo();
                            finish();
                        })
                        .setNegativeButton("Ne", null)
                        .show();
            }
        });
    }

    private android.nfc.NfcAdapter nfcAdapter;
    private si.ros.RosKasa.nfc.NfcHelper.OnNfcTagReadListener nfcTagListener;

    public void requestBluetoothPermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            boolean hasConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasScan = androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!hasConnect || !hasScan) {
                androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        new String[]{android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN},
                        1002
                );
            }
        }
    }

    private void initNfc() {
        try {
            nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this);
            if (nfcAdapter == null) {
                android.util.Log.w("MainActivity", "NFC strojna oprema ni na voljo na tej napravi.");
            } else if (!nfcAdapter.isEnabled()) {
                android.util.Log.w("MainActivity", "NFC adapter je izklopljen v nastavitvah sistema.");
                Toast.makeText(this, "NFC je izklopljen v nastavitvah telefona!", Toast.LENGTH_SHORT).show();
            } else {
                android.util.Log.i("MainActivity", "NFC adapter je pripravljen.");
            }
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Napaka pri preverjanju NFC: " + e.getMessage());
        }
    }

    public void setOnNfcTagReadListener(si.ros.RosKasa.nfc.NfcHelper.OnNfcTagReadListener listener) {
        this.nfcTagListener = listener;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startNfcListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopNfcListening();
    }

    private void startNfcListening() {
        if (Globals.getInstance().isAndroidNfcIzklop()) return;
        if (nfcAdapter == null) return;
        if (!nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Opozorilo: NFC je izklopljen v nastavitvah sistema!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Poskusi najprej z ReaderMode (najbolj zanesljiv na Android 4.4+)
        boolean readerModeOk = si.ros.RosKasa.nfc.NfcHelper.enableReaderMode(this, nfcAdapter, cardInfo -> {
            if (nfcTagListener != null) {
                nfcTagListener.onTagRead(cardInfo);
            }
        });

        // Kot rezervo omogoči tudi ForegroundDispatch
        if (!readerModeOk) {
            si.ros.RosKasa.nfc.NfcHelper.enableForegroundDispatch(this, nfcAdapter);
        }
    }

    private void stopNfcListening() {
        if (nfcAdapter == null) return;
        si.ros.RosKasa.nfc.NfcHelper.disableReaderMode(this, nfcAdapter);
        si.ros.RosKasa.nfc.NfcHelper.disableForegroundDispatch(this, nfcAdapter);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        si.ros.RosKasa.nfc.NfcHelper.NfcCardInfo cardInfo = si.ros.RosKasa.nfc.NfcHelper.extractTagFromIntent(intent);
        if (cardInfo != null && nfcTagListener != null) {
            nfcTagListener.onTagRead(cardInfo);
        }
    }

    private int lastOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED;

    @Override
    public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation;
            // Ponovno izriši aktivni fragment, da Android naloži ustrezen layout (layout/ <-> layout-sw600dp-land/)
            // brez izgube stanja računa v Globals.currentRacun
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
            if (current != null && !isFinishing() && !isDestroyed()) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .detach(current)
                        .commitNowAllowingStateLoss();
                getSupportFragmentManager()
                        .beginTransaction()
                        .attach(current)
                        .commitNowAllowingStateLoss();
            }
        }
    }

    public void navigateToFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    public interface PaymentResultListener {
        void onPaytenResult(int resultCode, android.content.Intent data);
        void onSixTapResult(int resultCode, android.content.Intent data);
    }

    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> paytenLauncher;
    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> sixTapLauncher;
    private PaymentResultListener paymentResultListener;

    private void initPaymentLaunchers() {
        paytenLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (paymentResultListener != null) {
                        paymentResultListener.onPaytenResult(result.getResultCode(), result.getData());
                    }
                }
        );

        sixTapLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (paymentResultListener != null) {
                        paymentResultListener.onSixTapResult(result.getResultCode(), result.getData());
                    } else {
                        handleStartupSixTapResult(result.getResultCode(), result.getData());
                    }
                }
        );
    }

    public void launchPayten(android.content.Intent intent, PaymentResultListener listener) {
        this.paymentResultListener = listener;
        if (paytenLauncher != null) {
            try {
                paytenLauncher.launch(intent);
            } catch (android.content.ActivityNotFoundException anfe) {
                Toast.makeText(this, "Aplikacija PayTen POS ni nameščena na napravi!", Toast.LENGTH_LONG).show();
                if (paymentResultListener != null) {
                    paymentResultListener.onPaytenResult(RESULT_CANCELED, null);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Napaka pri zagonu PayTen POS: " + e.getMessage(), Toast.LENGTH_LONG).show();
                if (paymentResultListener != null) {
                    paymentResultListener.onPaytenResult(RESULT_CANCELED, null);
                }
            }
        }
    }

    public void launchSixTap(android.content.Intent intent, PaymentResultListener listener) {
        this.paymentResultListener = listener;
        if (sixTapLauncher != null) {
            try {
                sixTapLauncher.launch(intent);
            } catch (android.content.ActivityNotFoundException anfe) {
                Toast.makeText(this, "Aplikacija Worldline Tap On ni nameščena na napravi!", Toast.LENGTH_LONG).show();
                if (paymentResultListener != null) {
                    paymentResultListener.onSixTapResult(RESULT_CANCELED, null);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Napaka pri zagonu Worldline Tap On: " + e.getMessage(), Toast.LENGTH_LONG).show();
                if (paymentResultListener != null) {
                    paymentResultListener.onSixTapResult(RESULT_CANCELED, null);
                }
            }
        }
    }

    public void setPaymentResultListener(PaymentResultListener listener) {
        this.paymentResultListener = listener;
    }

    private boolean tapOnRecoverPrijava = false;
    private int tapOnRecoverStevec = 0;

    public void resetTapOnRecoverPrijava() {
        this.tapOnRecoverPrijava = false;
        this.tapOnRecoverStevec = 0;
    }

    public void checkPaymentRecoveryOnStartup() {
        tapOnRecoverExecute();
    }

    public void tapOnRecoverExecute() {
        if (tapOnRecoverPrijava || tapOnRecoverStevec >= 1) {
            return;
        }
        tapOnRecoverPrijava = true;
        tapOnRecoverStevec++;

        Globals g = Globals.getInstance();
        if (!g.isSixTap()) {
            return;
        }
        if (!g.isKronologIzklop() && g.isDebugL1()) {
            g.vpisiKronologijo("SIXTAP VKLOPLJEN");
        }
        if (!g.isRecoverTapOn()) {
            return;
        }
        if (!g.isKronologIzklop() && g.isDebugL1()) {
            g.vpisiKronologijo("SIXTAP TryGetPendingPayment VKLOPLJEN");
        }

        // 1. Preveri, ali obstaja že potrjena POS transakcija
        si.ros.RosKasa.payment.models.SixTapResponse confirmedResp = new si.ros.RosKasa.payment.models.SixTapResponse();
        int[] confirmedState = new int[1];
        if (si.ros.RosKasa.payment.PaymentRecoveryManager.tryGetConfirmedPOSTransaction(this, confirmedResp, confirmedState)) {
            if (confirmedState[0] >= si.ros.RosKasa.payment.PaymentRecoveryManager.POS_STATE_POS_CONFIRMED && confirmedResp.getpRacunId() > 0) {
                android.util.Log.i("MainActivity", "TAP ON RECOVER: Najdena potrjena transakcija za R:" + confirmedResp.getpRacunId());
                bookConfirmedPOSTransaction(confirmedResp);
                return;
            }
        }

        // 2. Preveri nedokončano ali prekinjeno sejo v SharedPreferences
        boolean hasRecover = false;
        si.ros.RosKasa.payment.PaymentRecoveryManager.PendingPaymentInfo recoverInfo = null;
        if (g.isSixTapManualLast() || g.isSixTapAutoLast()) {
            recoverInfo = si.ros.RosKasa.payment.PaymentRecoveryManager.tryGetRecoveryPayment(this);
            hasRecover = (recoverInfo != null && recoverInfo.isValid());
        }

        si.ros.RosKasa.payment.PaymentRecoveryManager.PendingPaymentInfo pendingInfo = null;
        if (!hasRecover) {
            pendingInfo = si.ros.RosKasa.payment.PaymentRecoveryManager.tryGetPendingPayment(this);
        }

        si.ros.RosKasa.payment.PaymentRecoveryManager.PendingPaymentInfo targetInfo = hasRecover ? recoverInfo : pendingInfo;
        if (targetInfo == null || !targetInfo.isValid()) {
            return;
        }

        String zadnjiWpiSessionId = targetInfo.getEffectiveSessionId();
        String pomIntentRacunid = targetInfo.getEffectiveRacunId();
        final int zadnjiSixRacunId;
        try {
            zadnjiSixRacunId = Integer.parseInt(pomIntentRacunid);
        } catch (Exception e) {
            return;
        }

        g.setZadnjiSixRacunId(zadnjiSixRacunId);
        g.setZadnjiWpiSessionId(zadnjiWpiSessionId);

        if (!g.isKronologIzklop() && g.isDebugL0()) {
            g.vpisiKronologijo("SIXTAP RECOVER R:" + zadnjiSixRacunId + " SESSION_ID: " + zadnjiWpiSessionId);
        }

        // To prevent recursive action: po 1 ali 2 klicih LAST_TRANSACTION pobrišemo shrambo sej
        int retry = si.ros.RosKasa.payment.PaymentRecoveryManager.getRecoveryRetryCount(this);
        if (retry >= 2) {
            android.util.Log.w("MainActivity", "TAP ON RECOVER: Število poskusov >= 2, ponastavljam hrambo sej.");
            g.vpisiKronologijo("SIXTAP ClearPendingPayment retry >= 2 preklic obnove R:" + zadnjiSixRacunId);
            si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(this);
            return;
        }

        si.ros.RosKasa.payment.PaymentRecoveryManager.incrementRecoveryRetryCount(this);

        final String finalSessionId = zadnjiWpiSessionId;
        final AppPreferences prefs = new AppPreferences(this);
        final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();
        bg.execute(() -> {
            try {
                si.ros.RosKasa.models.RacunTp r = si.ros.RosKasa.soap.RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), zadnjiSixRacunId);
                if (r != null) {
                    r.preracunajVsote();
                    java.math.BigDecimal saldo = r.getZnesek().subtract(r.getPlacano());
                    Integer fiskalniId = r.getFiskalniRacunId();
                    if ((fiskalniId == null || fiskalniId == 0) && saldo.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Okrevanje: Preverjam prekinjeno plačilo za račun #" + zadnjiSixRacunId + "...", Toast.LENGTH_SHORT).show();
                            android.content.Intent lastIntent = si.ros.RosKasa.payment.SixTapPaymentService.buildPaymentIntent(
                                    MainActivity.this, zadnjiSixRacunId, 0, saldo, java.math.BigDecimal.ZERO,
                                    si.ros.RosKasa.payment.SixTapPaymentService.OP_LAST_TRANSACTION, null, finalSessionId
                            );
                            launchSixTap(lastIntent, new PaymentResultListener() {
                                @Override
                                public void onPaytenResult(int resultCode, android.content.Intent data) {}

                                @Override
                                public void onSixTapResult(int resultCode, android.content.Intent data) {
                                    handleStartupSixTapResult(resultCode, data, zadnjiSixRacunId);
                                }
                            });
                        });
                    } else {
                        // Račun je že plačan ali fiskaliziran
                        g.vpisiKronologijo("SIXTAP RECOVER R:" + zadnjiSixRacunId + " že plačan ali fiskaliziran, brišem začasne podatke.");
                        runOnUiThread(() -> si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(MainActivity.this));
                    }
                } else {
                    runOnUiThread(() -> si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(MainActivity.this));
                }
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "Napaka pri preverjanju računa ob recovery: " + e.getMessage());
            }
        });
    }

    private void handleStartupSixTapResult(int resultCode, android.content.Intent data) {
        handleStartupSixTapResult(resultCode, data, Globals.getInstance().getZadnjiSixRacunId());
    }

    private void handleStartupSixTapResult(int resultCode, android.content.Intent data, int expectedRacunId) {
        Globals.getInstance().setSixtapintransaction(false);
        if (resultCode == RESULT_OK && data != null) {
            si.ros.RosKasa.payment.models.SixTapResponse resp = si.ros.RosKasa.payment.SixTapPaymentService.parseResponseIntent(data);
            if (resp.getpRacunId() <= 0 && expectedRacunId > 0) {
                resp.setpRacunId(expectedRacunId);
            }
            if (resp.getpOperacija() == null || resp.getpOperacija().isEmpty()) {
                resp.setpOperacija(si.ros.RosKasa.payment.SixTapPaymentService.OP_LAST_TRANSACTION);
            }
            boolean hasRef = resp.getPaymentSolutionReference() != null && !resp.getPaymentSolutionReference().isEmpty();
            if (resp.isSuccess() && hasRef && resp.getpRacunId() > 0) {
                android.util.Log.i("MainActivity", "LAST_TRANSACTION ob okrevanju uspel za R:" + resp.getpRacunId());
                Globals.getInstance().vpisiKronologijo("SIXTAP LAST_TRANSACTION USPEH R:" + resp.getpRacunId() + " M_REF:" + resp.getPaymentSolutionReference());
                si.ros.RosKasa.payment.PaymentRecoveryManager.saveConfirmedPOSTransaction(this, resp, si.ros.RosKasa.payment.PaymentRecoveryManager.POS_STATE_POS_CONFIRMED);
                bookConfirmedPOSTransaction(resp);
            } else {
                Globals.getInstance().vpisiKronologijo("SIXTAP LAST_TRANSACTION NEUSPEH R:" + (resp.getpRacunId() > 0 ? resp.getpRacunId() : expectedRacunId));
                si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(this);
            }
        } else {
            Globals.getInstance().vpisiKronologijo("SIXTAP LAST_TRANSACTION PREKLICAN R:" + expectedRacunId);
            si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(this);
        }
    }

    public void bookConfirmedPOSTransaction(si.ros.RosKasa.payment.models.SixTapResponse resp) {
        final AppPreferences prefs = new AppPreferences(this);
        final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();
        bg.execute(() -> {
            try {
                int racunId = resp.getpRacunId();
                si.ros.RosKasa.models.RacunTp r = si.ros.RosKasa.soap.RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), racunId);
                if (r != null) {
                    r.preracunajVsote();
                    // Preveri, ali plačilo morda že obstaja
                    boolean zeVpisan = false;
                    if (r.getRacPlaci() != null) {
                        for (si.ros.RosKasa.models.PlaciloTp pl : r.getRacPlaci()) {
                            if (pl != null && !pl.isRowDeleted() && resp.getPaymentSolutionReference().equals(pl.getMRef())) {
                                zeVpisan = true;
                                break;
                            }
                        }
                    }

                    if (!zeVpisan) {
                        int placiloId = resp.getpPlaciloId() > 0 ? resp.getpPlaciloId() : (Globals.getInstance().getSixTapPlaciloId() > 0 ? Globals.getInstance().getSixTapPlaciloId() : 2);
                        java.math.BigDecimal znesekPOS = resp.getZnesekPOS().compareTo(java.math.BigDecimal.ZERO) > 0 ? resp.getZnesekPOS() : r.getZnesek().subtract(r.getPlacano());
                        java.math.BigDecimal napitnina = resp.getpNapitnina();
                        // 1. ZNESEK RAČUNA: Ob uspešni POS transakciji mora biti v DELNI_ZNESEK zapisan POS ZNESEK - NAPITNINA
                        java.math.BigDecimal znesekRacuna = (napitnina != null && napitnina.compareTo(java.math.BigDecimal.ZERO) > 0)
                                ? znesekPOS.subtract(napitnina)
                                : znesekPOS;
                        if (znesekRacuna.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                            znesekRacuna = znesekPOS;
                        }

                        si.ros.RosKasa.models.PlaciloTp pl = new si.ros.RosKasa.models.PlaciloTp(racunId, placiloId, znesekRacuna);
                        pl.setDelniZnesek(znesekRacuna);
                        pl.setZnesek(BigDecimal.ZERO);
                        pl.setPozicijaId(-1);
                        int kupecId = Globals.getInstance().resolvePosKupecId(placiloId, resp.getCardnumber(), resp.getClient(), resp.getMerchant(), resp.getApplicationIdentifier());
                        if (kupecId > 0) {
                            pl.setKupecId(kupecId);
                            pl.setPartnerId(kupecId);
                        }
                        pl.setStKartice(resp.getCardnumber());
                        pl.setMRef(resp.getPaymentSolutionReference());
                        pl.setWpiSessionId(resp.getpWpiSessionId());
                        if (napitnina != null && napitnina.compareTo(BigDecimal.ZERO) > 0) {
                            pl.setNapitnina(napitnina);
                        } else {
                            pl.setNapitnina(null);
                        }
                        if (r.getRacPozic() != null) {
                            for (si.ros.RosKasa.models.PozicijaTp p : r.getRacPozic()) {
                                if (p != null) p.setNeNarocaj(true);
                            }
                        }
                        if (r.getRacPlaci() == null) r.setRacPlaci(new java.util.ArrayList<>());
                        r.getRacPlaci().add(pl);
                        r.setPlacano(r.getPlacano().add(znesekRacuna));
                        r.preracunajVsote();
                        if (r.getPlacano().compareTo(r.getZnesek()) >= 0) {
                            r.setStatus(2);
                            r.setFiskalizacija(1);
                        } else {
                            r.setStatus(1);
                        }

                        // 1. setSlipEma
                        si.ros.RosKasa.models.SlipEmaTp slip = new si.ros.RosKasa.models.SlipEmaTp();
                        slip.setStevilkaRacuna(racunId);
                        slip.setPozicijaId(1);
                        slip.setSlipPrint(resp.getClient());
                        slip.setSlipPrints(resp.getMerchant());
                        slip.setStevilkaKartice(resp.getCardnumber());
                        slip.setUspelo("DA");
                        slip.setProjektId(3);
                        slip.setStType(resp.getpOperacija());
                        slip.setZnesek(znesekRacuna);
                        slip.setZnesekSlip(znesekPOS);
                        slip.setAcqTransRef(resp.getpWpiSessionId() != null && !resp.getpWpiSessionId().isEmpty() ? resp.getpWpiSessionId() : resp.getPaymentSolutionReference());
                        slip.setAppIdentifier(resp.getApplicationIdentifier());
                        slip.setAcqReference(resp.getAcqreference());
                        slip.setAuthReference(resp.getPaymentSolutionReference());
                        slip.setAvtorizacija("PAY");
                        Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma R:" + racunId + " Kartica:" + slip.getStevilkaKartice() + " Auth:" + slip.getAuthReference() + " M_REF: " + slip.getAcqTransRef() + " Op: " + slip.getStType());
                        boolean slipOk = RosKasaSoapClient.setSlipEma(prefs.getServerUrl(), prefs.getToken(), slip);
                        if (slipOk) {
                            Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma USPEH R:" + racunId);
                        } else {
                            Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma OPOZORILO: strežnik ni potrdil slipa R:" + racunId);
                        }

                        // 2. setRacun
                        int mobileId = 1;
                        try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                        si.ros.RosKasa.models.GetRacunRsTp res = si.ros.RosKasa.soap.RosKasaSoapClient.setRacun(prefs.getServerUrl(), prefs.getToken(), mobileId, r);
                        if (res != null && res.getRacGlava() != null) {
                            r = res.getRacGlava();
                        }

                        // 3. Tiskanje zaključenega računa
                        final si.ros.RosKasa.models.RacunTp finalR = r;
                        runOnUiThread(() -> {
                            si.ros.RosKasa.print.BluetoothPrintHelper.printReceiptComplete(MainActivity.this, finalR, null);
                            Toast.makeText(MainActivity.this, "Okrevanje: Račun #" + racunId + " je bil uspešno knjižen in natisnjen!", Toast.LENGTH_LONG).show();
                        });
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Napaka pri knjiženju potrjene transakcije: " + e.getMessage(), e);
                Globals.getInstance().vpisiKronologijo("SIXTAP Napaka pri knjiženju potrjene transakcije: " + e.getMessage());
            } finally {
                // 3. Po uspešnem klicu ali zaključku obvezno počistimo shrambo podatkov transakcije
                runOnUiThread(() -> si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(MainActivity.this));
            }
        });
    }
}
