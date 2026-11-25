package si.ros.RosKasa.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.MainActivity;
import si.ros.RosKasa.databinding.FragmentLoginBinding;
import si.ros.RosKasa.models.HitraTipkaTp;
import si.ros.RosKasa.models.LojalnostnaTp;
import si.ros.RosKasa.models.MobileSetupTp;
import si.ros.RosKasa.models.NacPlacTp;
import si.ros.RosKasa.models.OsebaTokenResult;
import si.ros.RosKasa.models.OsebaTp;
import si.ros.RosKasa.models.PraviceConsts;
import si.ros.RosKasa.nfc.NfcHelper;
import si.ros.RosKasa.soap.RosKasaSoapClient;

public class LoginFragment extends Fragment {

    private FragmentLoginBinding binding;
    private AppPreferences prefs;
    private int pinFailedAttempts = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        prefs = new AppPreferences(requireContext());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).resetTapOnRecoverPrijava();
        }

        prefs.loadSavedMobIni(Globals.getInstance());
        if (Globals.getInstance().getCachedOsebje().isEmpty()) {
            prefs.loadSavedSifranti();
        }

        updateUiMode();

        if (prefs.isRegistered()) {
            // getAppConfig vedno svež ob zagonu (skladno z Delphi SetupIzWeba(True) + CheckPovezava)
            if (!Globals.isNetworkAvailable(requireContext())) {
                Toast.makeText(requireContext(), "Opozorilo: Ni internetne povezave! Uporabljeni bodo lokalni podatki.", Toast.LENGTH_LONG).show();
            } else {
                executor.execute(() -> {
                    try {
                        int mId = 1;
                        try { mId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                        MobileSetupTp setup = RosKasaSoapClient.getAppConfig(prefs.getServerUrl(), prefs.getToken(), mId);
                        prefs.saveMobileSetup(setup);
                        mainHandler.post(() -> {
                            if (isAdded() && binding != null) {
                                updateUiMode();
                            }
                        });
                    } catch (Exception ignored) {}
                });
            }
        }

        final boolean[] isPasswordVisible = {false};
        binding.btnTogglePassword.setOnClickListener(v -> {
            isPasswordVisible[0] = !isPasswordVisible[0];
            if (isPasswordVisible[0]) {
                binding.etPassword.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
                binding.btnTogglePassword.setColorFilter(android.graphics.Color.parseColor("#4CAF50"));
            } else {
                binding.etPassword.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                binding.btnTogglePassword.setColorFilter(android.graphics.Color.parseColor("#CCCCCC"));
            }
            binding.etPassword.setSelection(binding.etPassword.getText().length());
        });

        binding.tvTitle.setOnLongClickListener(v -> {
            if (binding.btnResetConfig.getVisibility() != View.VISIBLE) {
                binding.btnResetConfig.setVisibility(View.VISIBLE);
                Toast.makeText(requireContext(), "Gumb za ponastavitev omogočen", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        binding.btnRegister.setOnClickListener(v -> handleRegistration());
        binding.btnLoginPin.setOnClickListener(v -> handlePinLogin());
        binding.etPinCode.setOnEditorActionListener((v, actionId, event) -> {
            handlePinLogin();
            return true;
        });

        binding.btnResetConfig.setOnClickListener(v -> {
            prefs.clearAll();
            updateUiMode();
            Toast.makeText(requireContext(), "Nastavitve ponastavljene", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateUiMode() {
        boolean hasSavedConfig = prefs.hasSavedUrl() && prefs.hasSavedToken();
        binding.btnResetConfig.setVisibility(hasSavedConfig ? View.GONE : View.VISIBLE);

        if (prefs.isRegistered()) {
            binding.containerFirstRun.setVisibility(View.GONE);
            binding.containerRegularLogin.setVisibility(View.VISIBLE);

            boolean isHid = Globals.getInstance().ispHidPrijava();
            if (isHid) {
                binding.layoutPinLogin.setVisibility(View.GONE);
                binding.tvNfcHint.setVisibility(View.VISIBLE);
            } else {
                binding.layoutPinLogin.setVisibility(View.VISIBLE);
                binding.tvNfcHint.setVisibility(View.VISIBLE);
                binding.etPinCode.post(() -> {
                    if (binding != null) binding.etPinCode.requestFocus();
                });
            }
        } else {
            binding.containerFirstRun.setVisibility(View.VISIBLE);
            binding.containerRegularLogin.setVisibility(View.GONE);
            if (binding.etServerUrl.getText().toString().trim().isEmpty()) {
                String savedUrl = prefs.getServerUrl();
                binding.etServerUrl.setText(savedUrl != null && !savedUrl.isEmpty() ? savedUrl : "https://web.ros.si/ora/r16f/asmx/kasa.asmx");
            }
        }
    }

    private void handleRegistration() {
        String rawUrl = binding.etServerUrl.getText().toString().trim();
        String mobileId = binding.etMobileId.getText().toString().trim();
        String uIme = binding.etUsername.getText().toString().trim();
        String geslo = binding.etPassword.getText().toString().trim();

        // Google Play Demo bypass (skladno z Delphi FormPrijava / demo registracija)
        if (("DemoGooglePlay".equalsIgnoreCase(uIme) && "1DemoApprove6840".equals(geslo))
                || "demo".equalsIgnoreCase(rawUrl)) {
            if (rawUrl.isEmpty() || "demo".equalsIgnoreCase(rawUrl)) {
                rawUrl = "https://demo.ros.si/KasaWS/KasaService.svc";
                binding.etServerUrl.setText(rawUrl);
            }
            if (mobileId.isEmpty() || "demo".equalsIgnoreCase(binding.etServerUrl.getText().toString().trim())) {
                mobileId = "6";
                binding.etMobileId.setText(mobileId);
            } else if ("DemoGooglePlay".equalsIgnoreCase(uIme)) {
                mobileId = "6";
                binding.etMobileId.setText(mobileId);
            }
            if (uIme.isEmpty()) {
                uIme = "DemoGooglePlay";
                binding.etUsername.setText(uIme);
            }
            if (geslo.isEmpty()) {
                geslo = "1DemoApprove6840";
                binding.etPassword.setText(geslo);
            }
        }

        if (rawUrl != null) {
            rawUrl = rawUrl.replaceAll("\\s+", "");
        }
        if (rawUrl == null || rawUrl.isEmpty() || mobileId.isEmpty() || uIme.isEmpty() || geslo.isEmpty()) {
            Toast.makeText(requireContext(), "Prosim izpolnite vsa polja!", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalMobileId = mobileId;
        final String finalUIme = uIme;
        final String finalGeslo = geslo;
        final String url = RosKasaSoapClient.formatEndpoint(rawUrl);
        if (!url.equals(rawUrl)) {
            binding.etServerUrl.setText(url);
        }

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.btnRegister.setEnabled(false);

        executor.execute(() -> {
            try {
                OsebaTokenResult result = RosKasaSoapClient.getOsebaToken(url, finalUIme, finalGeslo);
                if (result.isSuccess()) {
                    prefs.saveDeviceInfo(url, finalMobileId, result.getToken());

                    int mId = 1;
                    try { mId = Integer.parseInt(finalMobileId); } catch (Exception ignored) {}

                    // Delphi: Odgovor := R16K.Servis.aktivirajMobile(StrToInt(edMobileId.text), Token);
                    // Strežnik s tem postavi mobile.aktivnost na 'D'
                    try {
                        RosKasaSoapClient.aktivirajMobile(url, mId, result.getToken());
                    } catch (Exception aktEx) {
                        Log.w("LoginFragment", "aktivirajMobile opozorilo: " + aktEx.getMessage());
                    }

                    // Pridobi še zagonske nastavitve strežnika (MobileSetup)
                    try {
                        MobileSetupTp setup = RosKasaSoapClient.getAppConfig(url, result.getToken(), mId);
                        prefs.saveMobileSetup(setup);
                    } catch (Exception setupEx) {
                        // Ignoriramo napako pri pridobivanju setupa (uporabi se fallback)
                    }

                    mainHandler.post(() -> {
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnRegister.setEnabled(true);
                        Toast.makeText(requireContext(), "Registracija uspešna! Dobrodošli, " + result.getNaziv(), Toast.LENGTH_LONG).show();
                        updateUiMode();
                    });
                } else {
                    mainHandler.post(() -> {
                        binding.progressBar.setVisibility(View.GONE);
                        binding.btnRegister.setEnabled(true);
                        Toast.makeText(requireContext(), "Napaka: " + result.getFault(), Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.btnRegister.setEnabled(true);
                    Toast.makeText(requireContext(), "Povezava ni uspela: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setOnNfcTagReadListener(cardInfo -> {
                mainHandler.post(() -> handleNfcLogin(cardInfo));
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setOnNfcTagReadListener(null);
        }
    }

    private void handleNfcLogin(NfcHelper.NfcCardInfo cardInfo) {
        if (cardInfo == null) return;
        Globals g = Globals.getInstance();
        if (g.isAndroidNfcIzklop() || (!g.isPnfcprijava() && !g.ispHidPrijava())) {
            Log.d("LoginFragment", "NFC prijava je izklopljena (ANDROIDNFCIZKLOP / NFCPRIJAVA).");
            return;
        }
        Log.d("LoginFragment", "NFC kartica zaznana: " + cardInfo);
        if (g.getCachedOsebje().isEmpty()) {
            prefs.loadSavedSifranti();
        }

        // Poskusi najti osebo po standardni decimalni in hex obliki
        OsebaTp oseba = g.najdiOseboZaKartico(cardInfo.decimalId);
        if (oseba == null) {
            oseba = g.najdiOseboZaKartico(cardInfo.hexId);
        }
        // Poskusi še z obrnjenim vrstnim redom bajtov (Little Endian)
        if (oseba == null && cardInfo.reversedDecimalId != null && !cardInfo.reversedDecimalId.isEmpty()) {
            oseba = g.najdiOseboZaKartico(cardInfo.reversedDecimalId);
        }
        if (oseba == null && cardInfo.reversedHexId != null && !cardInfo.reversedHexId.isEmpty()) {
            oseba = g.najdiOseboZaKartico(cardInfo.reversedHexId);
        }

        if (oseba == null) {
            Toast.makeText(requireContext(), "Neznana RFID / NFC kartica (HEX: " + cardInfo.hexId + " / DEC: " + cardInfo.decimalId + ")", Toast.LENGTH_LONG).show();
            return;
        }

        potrdiPrijavoOsebe(oseba);
    }

    private void handlePinLogin() {
        String pin = binding.etPinCode.getText().toString().trim();
        if (pin.isEmpty() || "0".equals(pin)) {
            binding.etPinCode.setText("");
            Toast.makeText(requireContext(), "Vnesite veljavno PIN kodo!", Toast.LENGTH_SHORT).show();
            return;
        }

        Globals g = Globals.getInstance();
        if (g.getCachedOsebje().isEmpty()) {
            prefs.loadSavedSifranti();
        }

        OsebaTp oseba = g.najdiOseboZaPin(pin);
        if (oseba == null) {
            binding.etPinCode.setText("");
            pinFailedAttempts++;
            if (pinFailedAttempts >= 3) {
                Toast.makeText(requireContext(), "3 neuspešni poskusi prijave! Aplikacija se zapira.", Toast.LENGTH_LONG).show();
                if (getActivity() != null) {
                    getActivity().finishAffinity();
                }
                return;
            }
            Toast.makeText(requireContext(), "Napačna PIN koda! (Poskus " + pinFailedAttempts + "/3)", Toast.LENGTH_SHORT).show();
            return;
        }

        pinFailedAttempts = 0;
        binding.etPinCode.setText("");
        potrdiPrijavoOsebe(oseba);
    }

    private void potrdiPrijavoOsebe(OsebaTp oseba) {
        Globals g = Globals.getInstance();
        if (g.ispCheckPinPotekel() && oseba.isPinPotekel()) {
            Toast.makeText(requireContext(), "PIN kodi je potekel datum veljavnosti!", Toast.LENGTH_LONG).show();
            return;
        }

        if (!g.osebiDovoljeno(oseba.getOsebaId(), PraviceConsts.SLahkoDelaSKaso)) {
            Toast.makeText(requireContext(), "Oseba '" + oseba.getNaziv() + "' nima pravice za delo s kaso!", Toast.LENGTH_LONG).show();
            return;
        }

        g.setTekocaOseba(oseba);
        g.posljiOfflineKronologijo();
        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Prijava uspešna: " + oseba.getNaziv(), Toast.LENGTH_SHORT).show();

        // Osveži nastavitve in naloži 6 velikih šifrantov (iz lokalnega disk cache če je veljaven, sicer SOAP)
        final android.content.Context appCtx = requireContext().getApplicationContext();
        if (prefs.isRegistered()) {
            executor.execute(() -> {
                try {
                    int mId = 1;
                    try { mId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                    if (Globals.isNetworkAvailable(appCtx)) {
                        MobileSetupTp setup = RosKasaSoapClient.getAppConfig(prefs.getServerUrl(), prefs.getToken(), mId);
                        prefs.saveMobileSetup(setup);
                    }

                    // Enkratna SixTap recovery kontrola šele PO prijavi in osvežitvi getAppConfig
                    if (getActivity() instanceof MainActivity) {
                        MainActivity mainAct = (MainActivity) getActivity();
                        mainAct.runOnUiThread(mainAct::tapOnRecoverExecute);
                    }

                    // Preverjanje CENA2 (vikend / prazniki) pri zagonu programa
                    if (g.isPrazniki() && !g.isCena2Aktivna() && Globals.isNetworkAvailable(appCtx)) {
                        try {
                            boolean jePraznik = RosKasaSoapClient.vrniPraznike(prefs.getServerUrl(), prefs.getToken(), new Date(), g.getPraznikiObrat());
                            if (jePraznik) {
                                g.setCena2Aktivna(true);
                                g.vpisiKronologijo("Preklop cenika na cena2! - prazniki");
                            }
                        } catch (Exception ignored) {}
                    }
                    if (g.isCena2Vikend() && !g.isCena2Aktivna()) {
                        LocalDate today = LocalDate.now();
                        int dayOfWeek = today.getDayOfWeek().getValue();
                        boolean isWeekend = g.isPetekWend() ? (dayOfWeek >= 5) : (dayOfWeek >= 6);
                        if (isWeekend) {
                            g.setCena2Aktivna(true);
                            g.vpisiKronologijo("Preklop cenika na cena2! - CENA2VIKEND");
                        }
                    }

                    // Poskusi naložiti 6 velikih šifrantov iz disk JSON cache (CenikLokalno + CenikStDniObnova)
                    boolean loadedFromDiskCache = si.ros.RosKasa.cache.LocalCacheManager.loadLargeSifrantiIfValid(appCtx);
                    if (!loadedFromDiskCache && Globals.isNetworkAvailable(appCtx)) {
                        // 1. Plačilne metode (getNacPlac2)
                        try {
                            List<NacPlacTp> placila = RosKasaSoapClient.getNacPlac2(prefs.getServerUrl(), prefs.getToken(), mId);
                            Globals.getInstance().filterAndSetCachedPlacila(placila);
                        } catch (Exception ex) {
                            Log.w("LoginFragment", "Napaka pri nalaganju plačil: " + ex.getMessage());
                        }

                        // 2. Cenik (+ CenikVrVr + CenikVrCene)
                        int strmId = prefs.getHisObrat() > 0 ? prefs.getHisObrat() : 512200;
                        try {
                            List<CenikListAdapter.CenikItem> cenik = RosKasaSoapClient.getCenik(prefs.getServerUrl(), prefs.getToken(), strmId);
                            if (cenik != null && !cenik.isEmpty()) {
                                Globals.getInstance().setCachedCenik(cenik);
                            }
                        } catch (Exception ex) {
                            Log.w("LoginFragment", "Napaka pri prednalaganju cenika: " + ex.getMessage());
                        }

                        // 3. Hitre tipke
                        int tipkePosId = prefs.getTipkePosId();
                        if (tipkePosId > 0) {
                            try {
                                List<HitraTipkaTp> tipke = RosKasaSoapClient.getHitreTipke(prefs.getServerUrl(), prefs.getToken(), tipkePosId);
                                if (tipke != null && !tipke.isEmpty()) {
                                    Globals.getInstance().setCachedHitreTipke(tipke);
                                }
                            } catch (Exception ex) {
                                Log.w("LoginFragment", "Napaka pri prednalaganju hitrih tipk: " + ex.getMessage());
                            }
                        }

                        // 4. Dodatki
                        try {
                            RosKasaSoapClient.getDodatki(prefs.getServerUrl(), prefs.getToken());
                        } catch (Exception ignored) {}

                        // 5. Storno razlogi
                        try {
                            List<si.ros.RosKasa.models.StornoRazlogTp> sr = RosKasaSoapClient.getStornoRazlogi(prefs.getServerUrl(), prefs.getToken());
                            if (sr != null && !sr.isEmpty()) Globals.getInstance().setCachedStornoRazlogi(sr);
                        } catch (Exception ignored) {}

                        // 6. Lojalnostni popusti
                        if (Globals.getInstance().ispLojalnostPopust()) {
                            try {
                                List<LojalnostnaTp> loj = RosKasaSoapClient.getLojalnostna(prefs.getServerUrl(), prefs.getToken());
                                if (loj != null && !loj.isEmpty()) {
                                    Globals.getInstance().setCachedLojalnostna(loj);
                                }
                            } catch (Exception ex) {
                                Log.w("LoginFragment", "Napaka pri prednalaganju lojalnostnih popustov: " + ex.getMessage());
                            }
                        }

                        if (g.isCenikLokalno()) {
                            si.ros.RosKasa.cache.LocalCacheManager.saveLargeSifranti(appCtx);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

        if (getActivity() instanceof MainActivity) {
            MainActivity mainAct = (MainActivity) getActivity();
            mainAct.navigateToFragment(new MizeFragment());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
