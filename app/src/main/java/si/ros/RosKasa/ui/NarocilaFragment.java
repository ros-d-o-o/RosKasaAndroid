package si.ros.RosKasa.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.MainActivity;
import si.ros.RosKasa.databinding.FragmentNarocilaBinding;
import si.ros.RosKasa.models.GetRacunRsTp;
import si.ros.RosKasa.models.CenikVrVrTp;
import si.ros.RosKasa.models.DelovniNalogTp;
import si.ros.RosKasa.models.SlipEmaTp;
import si.ros.RosKasa.models.HitraTipkaTp;
import si.ros.RosKasa.models.KartprijTp;
import si.ros.RosKasa.models.NacPlacTp;
import si.ros.RosKasa.models.NarociloItem;
import si.ros.RosKasa.models.PlaciloTp;
import si.ros.RosKasa.models.PozicijaTp;
import si.ros.RosKasa.models.RacunTp;
import si.ros.RosKasa.payment.PaymentRecoveryManager;
import si.ros.RosKasa.payment.PaytenPaymentService;
import si.ros.RosKasa.payment.models.PaytenResponse;
import si.ros.RosKasa.payment.SixTapPaymentService;
import si.ros.RosKasa.payment.models.SixTapResponse;
import si.ros.RosKasa.print.BluetoothPrintHelper;
import si.ros.RosKasa.soap.RosKasaSoapClient;
import si.ros.RosKasa.soap.VersionConflictException;

public class NarocilaFragment extends Fragment {

    private static final String TAG = "NarocilaFragment";

    private FragmentNarocilaBinding binding;
    private AppPreferences prefs;

    private NarociloItemAdapter orderAdapter;
    private QuickKeyAdapter quickKeyAdapter;
    private CenikListAdapter cenikListAdapter;

    private RacunTp currentRacun;
    private int activeRacunId = 0;

    private final List<NarociloItem> orderItems = new ArrayList<>();
    private final List<CenikListAdapter.CenikItem> cenikItems = new ArrayList<>();
    private final List<HitraTipkaTp> allApiHitreTipke = new ArrayList<>();

    private int currentSkupinaId = 1;
    private final Stack<Integer> skupinaHistory = new Stack<>();

    private String activeMarker = "Miza 5";
    private boolean hasUnsavedChanges = false;
    private volatile boolean isOperationInProgress = false;
    private long lastActionTime = 0;
    private static final long DEBOUNCE_DELAY = 1000;
    private boolean cena2LockedByHolidayOrWeekend = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentNarocilaBinding.inflate(inflater, container, false);
        prefs = new AppPreferences(requireContext());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (!prefs.getActiveMarker().isEmpty()) {
            activeMarker = prefs.getActiveMarker();
        }

        setupOrderRecyclerView();
        setupQuickKeysRecyclerView();
        setupCenikListRecyclerView();
        setupNavigationButtons();

        loadInitialOrderData();
        loadCenikItems();
        loadHitreTipkeFromApi();
        cena2VikendPrazniki();
        updateGumbNarocilaLevo();
        updateQuickPaymentHotkeys();
    }

    @Override
    public void onResume() {
        super.onResume();
        cena2VikendPrazniki();
        updateGumbNarocilaLevo();
        updateQuickPaymentHotkeys();
    }

    private long lastOrderClickTime = 0;
    private int lastOrderClickPos = -1;

    private void setupOrderRecyclerView() {
        orderAdapter = new NarociloItemAdapter((item, position) -> {
            long now = System.currentTimeMillis();
            if (position == lastOrderClickPos && (now - lastOrderClickTime < 600)) {
                // Dvojni klik (double-tap) odpre dialog za urejanje vrstice naročila
                odpriEditPozicijeDialog(item);
                lastOrderClickPos = -1;
                lastOrderClickTime = 0;
            } else {
                lastOrderClickTime = now;
                lastOrderClickPos = position;
            }
        });
        binding.rvNarociloItems.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvNarociloItems.setAdapter(orderAdapter);
    }

    private void setupQuickKeysRecyclerView() {
        int spanCount = DeviceLayoutHelper.getQuickKeysSpanCount(requireContext());
        int maxKeys = DeviceLayoutHelper.getQuickKeysMaxCount(requireContext());

        quickKeyAdapter = new QuickKeyAdapter(key -> {
            if (key.isBack) {
                // Povratek na prejšnjo skupino ali v skupino 1
                if (!skupinaHistory.isEmpty()) {
                    currentSkupinaId = skupinaHistory.pop();
                } else {
                    currentSkupinaId = 1;
                }
                displayQuickKeysForGroup(currentSkupinaId);
            } else if (key.isCategory) {
                // Klik na skupino -> drill-in v podskupino
                if (key.categoryTargetId != null && key.categoryTargetId > 0) {
                    skupinaHistory.push(currentSkupinaId);
                    currentSkupinaId = key.categoryTargetId;
                    displayQuickKeysForGroup(currentSkupinaId);
                } else {
                    showArticlesForCategoryFallback(key.title);
                }
            } else if (key.title != null && !key.title.trim().isEmpty()) {
                handleQuickKeyBooking(key);
            }
        });
        quickKeyAdapter.setMaxKeys(maxKeys);

        binding.rvQuickKeys.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        binding.rvQuickKeys.setAdapter(quickKeyAdapter);
    }

    private void setupCenikListRecyclerView() {
        cenikListAdapter = new CenikListAdapter(this::handleCenikItemBooking);
        binding.rvCenikList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvCenikList.setAdapter(cenikListAdapter);

        binding.etSearchCenik.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (cenikListAdapter != null) {
                    cenikListAdapter.filter(s != null ? s.toString() : "");
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.etSearchCenik.setText("");
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int spanCount = DeviceLayoutHelper.getQuickKeysSpanCount(requireContext());
        int maxKeys = DeviceLayoutHelper.getQuickKeysMaxCount(requireContext());
        if (quickKeyAdapter != null) {
            quickKeyAdapter.setMaxKeys(maxKeys);
        }
        if (binding != null && binding.rvQuickKeys != null) {
            binding.rvQuickKeys.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        }
        displayQuickKeysForGroup(currentSkupinaId);
    }

    private void loadInitialOrderData() {
        activeRacunId = prefs.getActiveRacunId();
        if (activeRacunId > 0) {
            disableEkran("Nalaganje računa #" + activeRacunId + "...");
            executor.execute(() -> {
                try {
                    RacunTp loaded = RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), activeRacunId);
                    mainHandler.post(() -> {
                        if (loaded != null) {
                            currentRacun = loaded;
                            hasUnsavedChanges = false;
                            Globals.getInstance().setCurrentRacun(loaded);
                            if (loaded.getMarker() != null && !loaded.getMarker().trim().isEmpty()) {
                                activeMarker = loaded.getMarker().trim();
                                prefs.setActiveMarker(activeMarker);
                            }
                            populateOrderItemsFromCurrentRacun();
                        } else {
                            initNewRacun();
                        }
                        enableEkran();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        enableEkran();
                        Toast.makeText(requireContext(), "Napaka pri nalaganju računa: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        initNewRacun();
                    });
                }
            });
        } else {
            initNewRacun();
        }
    }

    private void initNewRacun() {
        hasUnsavedChanges = false;
        int newId = Globals.getNextNegativeRacunId();
        currentRacun = new RacunTp(newId, activeMarker);
        currentRacun.setStatus(1);
        int tekocaOseba = Globals.getInstance().getTekocaOsebaId();
        currentRacun.setKasiral(tekocaOseba > 0 ? tekocaOseba : Globals.getInstance().getTekocaOsebaId());
        int toc = prefs.getTocilnicaId() > 0 ? prefs.getTocilnicaId() : 0;
        currentRacun.setTocilnicaId(toc);
        int fpos = prefs.getfPosId() > 0 ? prefs.getfPosId() : 0;
        currentRacun.setfPosId(fpos);
        int fpp = prefs.getfPoslovniProstorId() > 0 ? prefs.getfPoslovniProstorId() : 0;
        currentRacun.setfPoslovniProstorId(fpp);
        currentRacun.setTipRacuna(1);
        currentRacun.setStPogrinjkov(1);
        currentRacun.setStKopij(0);
        currentRacun.setVerzijaZapisa(0);
        Globals.getInstance().setCurrentRacun(currentRacun);
        orderItems.clear();
        updateOrderSummary();
    }

    private void populateOrderItemsFromCurrentRacun() {
        orderItems.clear();
        if (currentRacun != null && currentRacun.getRacPozic() != null) {
            java.util.Set<Integer> processedPaketi = new java.util.HashSet<>();

            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p == null || p.isRowDeleted()) continue;

                if (p.getPaketDistinct() != null && p.getPaketDistinct() > 0) {
                    int pDist = p.getPaketDistinct();
                    if (processedPaketi.contains(pDist)) {
                        continue;
                    }
                    processedPaketi.add(pDist);

                    int pNivo4 = (p.getPaketNivo4Id() != null && p.getPaketNivo4Id() > 0) ? p.getPaketNivo4Id() : 0;
                    String pNaziv = Globals.getInstance().findNazivByNivo4Id(pNivo4);
                    if (pNaziv == null || pNaziv.trim().isEmpty()) {
                        pNaziv = "Paket #" + pNivo4;
                    }

                    double pKol = (p.getPaketKol() != null && p.getPaketKol().compareTo(BigDecimal.ZERO) > 0)
                            ? p.getPaketKol().doubleValue() : 1.0;

                    BigDecimal pZnesek = BigDecimal.ZERO;
                    BigDecimal pPopust = BigDecimal.ZERO;
                    BigDecimal pLojalnost = BigDecimal.ZERO;

                    for (PozicijaTp comp : currentRacun.getRacPozic()) {
                        if (comp != null && !comp.isRowDeleted() && comp.getPaketDistinct() != null && comp.getPaketDistinct() == pDist) {
                            if (comp.getZnesek() != null) {
                                pZnesek = pZnesek.add(comp.getZnesek());
                            }
                            if (comp.getZnesekPopust() != null && comp.getZnesekPopust().abs().compareTo(BigDecimal.ZERO) > 0) {
                                pPopust = pPopust.add(comp.getZnesekPopust().abs());
                            }
                            if (comp.getZnesekLojalnost() != null && comp.getZnesekLojalnost().abs().compareTo(BigDecimal.ZERO) > 0) {
                                pLojalnost = pLojalnost.add(comp.getZnesekLojalnost().abs());
                            }
                        }
                    }

                    BigDecimal pNeto = pZnesek.subtract(pPopust).subtract(pLojalnost);
                    if (pZnesek.compareTo(BigDecimal.ZERO) > 0 && pNeto.compareTo(BigDecimal.ZERO) < 0) {
                        pNeto = BigDecimal.ZERO;
                    }
                    BigDecimal pCena = pKol > 0
                            ? pZnesek.divide(BigDecimal.valueOf(pKol), 2, RoundingMode.HALF_UP)
                            : pZnesek;

                    NarociloItem item = new NarociloItem(
                            pNivo4,
                            "[PAKET] " + pNaziv,
                            pCena,
                            pKol,
                            1.0,
                            1,
                            1,
                            p.getTarifaId() != null ? p.getTarifaId() : 1,
                            p.getIzvorStrmId() != null ? p.getIzvorStrmId() : 1,
                            p.getIzvorPrihodekId() != null ? p.getIzvorPrihodekId() : 1,
                            p.getStopnjaDavka(),
                            0
                    );
                    item.setPozicijaId(p.getPozicijaId());
                    item.setPaketDistinct(pDist);
                    item.setPaketNivo4Id(pNivo4);
                    item.setCustomZnesek(pNeto);
                    item.setZnesekPopust(pPopust);
                    item.setHod(p.getHod());
                    orderItems.add(item);

                } else {
                    String naziv = p.getNaziv();
                    if ((naziv == null || naziv.trim().isEmpty()) && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                        naziv = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                        if (naziv != null && !naziv.trim().isEmpty()) {
                            p.setNaziv(naziv);
                        }
                    }
                    if (naziv == null || naziv.trim().isEmpty()) {
                        naziv = "Artikel #" + (p.getNivo4Id() != null ? p.getNivo4Id() : 0);
                    }

                    NarociloItem item = new NarociloItem(
                            p.getNivo4Id() != null ? p.getNivo4Id() : 0,
                            naziv,
                            p.getCena(),
                            p.getKolicina(),
                            p.getEnotaProdajeId() != null ? p.getEnotaProdajeId().doubleValue() : 1.0,
                            0,
                            1,
                            p.getTarifaId() != null ? p.getTarifaId() : 1,
                            p.getIzvorStrmId() != null ? p.getIzvorStrmId() : 1,
                            p.getIzvorPrihodekId() != null ? p.getIzvorPrihodekId() : 1,
                            p.getStopnjaDavka(),
                            0
                    );
                    item.setPozicijaId(p.getPozicijaId());
                    item.setHod(p.getHod());
                    if (p.getZnesekPopust() != null && p.getZnesekPopust().abs().compareTo(BigDecimal.ZERO) > 0) {
                        item.setZnesekPopust(p.getZnesekPopust().abs());
                    }
                    orderItems.add(item);
                }
            }
        }
        updateOrderSummary();
    }

    private void refreshOrderArticleNames() {
        boolean updated = false;
        if (orderItems != null) {
            for (NarociloItem item : orderItems) {
                if (item.getNivo4Id() > 0 && (item.getNaziv() == null || item.getNaziv().trim().isEmpty() || item.getNaziv().startsWith("Artikel #"))) {
                    String resolved = Globals.getInstance().findNazivByNivo4Id(item.getNivo4Id());
                    if (resolved != null && !resolved.trim().isEmpty()) {
                        item.setNaziv(resolved);
                        updated = true;
                    }
                }
            }
        }
        if (currentRacun != null && currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null && p.getNivo4Id() != null && p.getNivo4Id() > 0 && (p.getNaziv() == null || p.getNaziv().trim().isEmpty() || p.getNaziv().startsWith("Artikel #"))) {
                    String resolved = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                    if (resolved != null && !resolved.trim().isEmpty()) {
                        p.setNaziv(resolved);
                    }
                }
            }
        }
        if (updated && orderAdapter != null) {
            orderAdapter.notifyDataSetChanged();
            updateOrderSummary();
        }
    }

    private String getZaklenjenRazlog() {
        if (currentRacun == null) return null;
        Integer status = currentRacun.getStatus();
        if (status != null && status >= 2) {
            return "STATUS=" + status;
        }
        if (currentRacun.isPlacan()) {
            return "PLAČAN";
        }
        // Delphi FormKasaMobile.pas:5208, 8132: PreprecimEditStornoPozicij zaklene urejanje pozicij na novem računu iz storna
        if (currentRacun.getStornoOriginal() != null && currentRacun.getStornoOriginal() != 0
                && (currentRacun.getUrejamStorno() == null || currentRacun.getUrejamStorno() == 0)) {
            if (Globals.getInstance().isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SpreprecimEditStornoPozicij)
                    || (currentRacun.getUrejamStorno() != null && currentRacun.getUrejamStorno() == 0)) {
                return "STORNO_ORIG=" + currentRacun.getStornoOriginal();
            }
        }
        // Zaklep ob delnih plačilih (RACPLACI > 0)
        if (currentRacun.getRacPlaci() != null) {
            for (PlaciloTp pl : currentRacun.getRacPlaci()) {
                if (pl != null && !pl.isRowDeleted() && pl.getPlaciloId() > 0) {
                    return "DELNO PLAČILO (PL=" + pl.getPlaciloId() + ")";
                }
            }
        }
        if (currentRacun.getStornoRacunId() != null && currentRacun.getStornoRacunId() > 0) {
            return "STORNIRAN (#" + currentRacun.getStornoRacunId() + ")";
        }
        if (currentRacun.getLojalnostId() != null && currentRacun.getLojalnostId() > 0) {
            return "LOJALNOST=" + currentRacun.getLojalnostId();
        }
        if (currentRacun.getAkcijaId() != null && currentRacun.getAkcijaId() > 0) {
            return "AKCIJA=" + currentRacun.getAkcijaId();
        }
        String bkarta = currentRacun.getBkartaId() != null ? currentRacun.getBkartaId().trim() : "";
        if (!bkarta.isEmpty() && !"0".equals(bkarta) && !"null".equalsIgnoreCase(bkarta) && !"anyType{}".equals(bkarta)) {
            return "BKARTA=" + bkarta;
        }
        return null;
    }

    private boolean isRacunZaklenjen() {
        return getZaklenjenRazlog() != null;
    }

    private final Runnable enableEkranSafetyRunnable = () -> {
        if (isOperationInProgress) {
            Globals.getInstance().vpisiKronologijo("TIMERENABLEEKRAN: Samodejni odklep zaslona (NarocilaFragment)");
            enableEkran();
        }
    };

    private void disableEkran(String msg) {
        if (binding == null) return;
        isOperationInProgress = true;
        mainHandler.removeCallbacks(enableEkranSafetyRunnable);
        long timeoutMs = Globals.getInstance().getTimerEnableEkran() > 0 ? Globals.getInstance().getTimerEnableEkran() : 10000L;
        mainHandler.postDelayed(enableEkranSafetyRunnable, timeoutMs);
        binding.btnNarociPost.setEnabled(false);
        binding.btnBrisanje.setEnabled(false);
        binding.btnCenikTipke.setEnabled(false);
        binding.btnPreklopiCenik.setEnabled(false);
        binding.btnHodi.setEnabled(false);
        binding.btnPopust.setEnabled(false);
        binding.btnGostHotela.setEnabled(false);
        binding.btnKreditnaPos.setEnabled(false);
        binding.btnKredRocno.setEnabled(false);
        binding.btnGotovina.setEnabled(false);
        binding.btnNavMize.setEnabled(false);
        binding.btnNavRacuni.setEnabled(false);
        binding.btnNavPlacila.setEnabled(false);
        if (msg != null && !msg.isEmpty() && getContext() != null) {
            // [DEBUG-TOAST] Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void enableEkran() {
        mainHandler.removeCallbacks(enableEkranSafetyRunnable);
        if (binding == null) return;
        isOperationInProgress = false;
        boolean zaklenjen = isRacunZaklenjen();
        binding.btnNarociPost.setEnabled(!zaklenjen);
        binding.btnBrisanje.setEnabled(!zaklenjen);
        binding.btnCenikTipke.setEnabled(true);
        binding.btnHodi.setEnabled(true);
        binding.btnPopust.setEnabled(!zaklenjen);
        binding.btnGostHotela.setEnabled(!zaklenjen);
        binding.btnKreditnaPos.setEnabled(!zaklenjen);
        binding.btnKredRocno.setEnabled(!zaklenjen);

        updateQuickPaymentHotkeys();
        updatePreklopiCenikButtonState();
        updateGumbNarocilaLevo();

        binding.btnNavMize.setEnabled(true);
        binding.btnNavRacuni.setEnabled(true);
        binding.btnNavPlacila.setEnabled(true);
    }

    private void updateQuickPaymentHotkeys() {
        if (binding == null) return;
        Globals g = Globals.getInstance();
        boolean zaklenjen = isRacunZaklenjen();

        // 1. Tipka GOTOVINA (TIPKAGOTOVINA v MobIni, privzeto true)
        boolean gotovinaAktivna = g.isTipkaGotovina();
        if (gotovinaAktivna) {
            binding.btnGotovina.setVisibility(View.VISIBLE);
            binding.btnGotovina.setEnabled(!zaklenjen);
            NacPlacTp npGotovina = g.getPlaciloById(1);
            String nazivGotovina = (npGotovina != null && npGotovina.getNaziv() != null && !npGotovina.getNaziv().trim().isEmpty())
                    ? npGotovina.getNaziv()
                    : "Gotovina";
            binding.btnGotovina.setText(nazivGotovina);
            binding.btnGotovina.setOnClickListener(v -> izvediHitroPlacilo(1));
        } else {
            binding.btnGotovina.setVisibility(View.INVISIBLE);
            binding.btnGotovina.setEnabled(false);
            binding.btnGotovina.setOnClickListener(null);
        }

        // 2. HOTKEY 3 (HOTKEY3PLACILOID v MobIni) -> gumb btnGostHotela
        final int hk3PlaciloId = g.getHotkey3();
        if (hk3PlaciloId > 0) {
            binding.btnGostHotela.setVisibility(View.VISIBLE);
            binding.btnGostHotela.setEnabled(!zaklenjen);
            NacPlacTp np3 = g.getPlaciloById(hk3PlaciloId);
            String naziv3 = (np3 != null && np3.getNaziv() != null && !np3.getNaziv().trim().isEmpty())
                    ? np3.getNaziv()
                    : ("Plačilo " + hk3PlaciloId);
            binding.btnGostHotela.setText(naziv3);
            binding.btnGostHotela.setOnClickListener(v -> izvediHitroPlacilo(hk3PlaciloId));
        } else {
            binding.btnGostHotela.setVisibility(View.INVISIBLE);
            binding.btnGostHotela.setEnabled(false);
            binding.btnGostHotela.setOnClickListener(null);
        }

        // 3. HOTKEY 4 (HOTKEY4PLACILOID v MobIni) -> gumb btnKreditnaPos
        final int hk4PlaciloId = g.getHotkey4();
        if (hk4PlaciloId > 0) {
            binding.btnKreditnaPos.setVisibility(View.VISIBLE);
            binding.btnKreditnaPos.setEnabled(!zaklenjen);
            NacPlacTp np4 = g.getPlaciloById(hk4PlaciloId);
            String naziv4 = (np4 != null && np4.getNaziv() != null && !np4.getNaziv().trim().isEmpty())
                    ? np4.getNaziv()
                    : ("Plačilo " + hk4PlaciloId);
            binding.btnKreditnaPos.setText(naziv4);
            binding.btnKreditnaPos.setOnClickListener(v -> izvediHitroPlacilo(hk4PlaciloId));
        } else {
            binding.btnKreditnaPos.setVisibility(View.INVISIBLE);
            binding.btnKreditnaPos.setEnabled(false);
            binding.btnKreditnaPos.setOnClickListener(null);
        }

        // 4. HOTKEY 5 (HOTKEY5PLACILOID v MobIni) -> gumb btnKredRocno
        final int hk5PlaciloId = g.getHotkey5();
        if (hk5PlaciloId > 0) {
            binding.btnKredRocno.setVisibility(View.VISIBLE);
            binding.btnKredRocno.setEnabled(!zaklenjen);
            NacPlacTp np5 = g.getPlaciloById(hk5PlaciloId);
            String naziv5 = (np5 != null && np5.getNaziv() != null && !np5.getNaziv().trim().isEmpty())
                    ? np5.getNaziv()
                    : ("Plačilo " + hk5PlaciloId);
            binding.btnKredRocno.setText(naziv5);
            binding.btnKredRocno.setOnClickListener(v -> izvediHitroPlacilo(hk5PlaciloId));
        } else {
            binding.btnKredRocno.setVisibility(View.INVISIBLE);
            binding.btnKredRocno.setEnabled(false);
            binding.btnKredRocno.setOnClickListener(null);
        }
    }

    public void updateGumbNarocilaLevo() {
        if (binding == null) return;
        Globals g = Globals.getInstance();

        if (g.isLokatorji()) {
            if (currentRacun != null && currentRacun.getLokator() != null && !currentRacun.getLokator().trim().isEmpty()) {
                binding.btnPopust.setText("Lokator:\n" + currentRacun.getLokator().trim());
            } else {
                binding.btnPopust.setText("Lokator");
            }
            binding.btnPopust.setVisibility(View.VISIBLE);
        } else if (g.isHodNarocila()) {
            String th = g.getTekociHod();
            if (th != null && !th.trim().isEmpty()) {
                binding.btnPopust.setText("HOD: " + th.trim());
            } else {
                binding.btnPopust.setText("HOD ");
            }
            binding.btnPopust.setVisibility(View.VISIBLE);
        } else if (g.isDnCenik() && !g.isHodNarocila() && !g.isLokatorji()) {
            binding.btnPopust.setText("DN");
            binding.btnPopust.setVisibility(View.VISIBLE);
        } else {
            binding.btnPopust.setText("Popust");
            binding.btnPopust.setVisibility(View.VISIBLE);
        }
    }

    public void updatePreklopiCenikButtonState() {
        if (binding == null) return;
        Globals g = Globals.getInstance();
        if (g.isCena2Aktivna()) {
            binding.btnPreklopiCenik.setText("CENA 2");
        } else {
            binding.btnPreklopiCenik.setText("PREKLOPI\nCENIK");
        }

        boolean enablePreklop = true;
        if (g.isCena2PreklopOff()) {
            enablePreklop = false;
        } else if (!g.isLahkoPreklopiCenik()) {
            enablePreklop = false;
        } else if (cena2LockedByHolidayOrWeekend) {
            enablePreklop = false;
        }
        binding.btnPreklopiCenik.setEnabled(enablePreklop);
    }

    public void cena2VikendPrazniki() {
        Globals g = Globals.getInstance();

        // 1. Prazniki (če so vklopljeni in CENA2 še ni aktivna)
        if (g.isPrazniki() && !g.isCena2Aktivna()) {
            executor.execute(() -> {
                try {
                    int obratId = g.getPraznikiObrat();
                    boolean jePraznik = RosKasaSoapClient.vrniPraznike(
                            prefs.getServerUrl(), prefs.getToken(), new Date(), obratId
                    );
                    if (jePraznik && isAdded() && getActivity() != null) {
                        mainHandler.post(() -> {
                            if (!g.isCena2Aktivna()) {
                                g.setCena2Aktivna(true);
                                g.vpisiKronologijo("Preklop cenika na cena2! - prazniki");
                                if (g.isCena2PreklopOff()) {
                                    cena2LockedByHolidayOrWeekend = true;
                                }
                                updatePreklopiCenikButtonState();
                                osveziCenePoPreklopu();
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Napaka pri preverjanju praznikov: " + e.getMessage());
                }
            });
        }

        // 2. Vikend (CENA2VIKEND in not CENA2AKTIVNA)
        if (g.isCena2Vikend() && !g.isCena2Aktivna()) {
            java.time.LocalDate today = java.time.LocalDate.now();
            int dayOfWeek = today.getDayOfWeek().getValue(); // 1 = PON .. 5 = PET, 6 = SOB, 7 = NED
            boolean isWeekend;
            if (g.isPetekWend()) {
                isWeekend = (dayOfWeek == 5 || dayOfWeek == 6 || dayOfWeek == 7);
            } else {
                isWeekend = (dayOfWeek == 6 || dayOfWeek == 7);
            }
            if (isWeekend) {
                g.setCena2Aktivna(true);
                g.vpisiKronologijo("Preklop cenika na cena2! - CENA2VIKEND");
                if (g.isCena2PreklopOff()) {
                    cena2LockedByHolidayOrWeekend = true;
                }
                updatePreklopiCenikButtonState();
                osveziCenePoPreklopu();
            }
        }

        if (g.isCena2Aktivna() && g.isCena2PreklopOff()) {
            cena2LockedByHolidayOrWeekend = true;
        }

        updatePreklopiCenikButtonState();
    }

    private void osveziCenePoPreklopu() {
        if (Globals.getInstance().hasCachedCenik()) {
            for (CenikListAdapter.CenikItem ci : Globals.getInstance().getCachedCenik()) {
                if (ci != null) ci.updateCenaForCurrentState();
            }
        }
        for (CenikListAdapter.CenikItem ci : cenikItems) {
            if (ci != null) ci.updateCenaForCurrentState();
        }
        cenikListAdapter.notifyDataSetChanged();
        displayQuickKeysForGroup(currentSkupinaId);
    }

    private void loadCenikItems() {
        if (Globals.getInstance().hasCachedCenik()) {
            cenikItems.clear();
            cenikItems.addAll(processAndSortCenikItems(Globals.getInstance().getCachedCenik()));
            cenikListAdapter.setItems(cenikItems);
            displayQuickKeysForGroup(currentSkupinaId);
            return;
        }

        cenikItems.clear();
        int stroskovnoId = prefs.getHisObrat();
        if (stroskovnoId == 0) stroskovnoId = 0;

        final int finalStroskovnoId = stroskovnoId;

        executor.execute(() -> {
            try {
                List<CenikListAdapter.CenikItem> result = RosKasaSoapClient.getCenik(
                        prefs.getServerUrl(),
                        prefs.getToken(),
                        finalStroskovnoId
                );

                mainHandler.post(() -> {
                    if (result != null && !result.isEmpty()) {
                        Globals.getInstance().setCachedCenik(result);
                        refreshOrderArticleNames();
                        cenikItems.clear();
                        cenikItems.addAll(processAndSortCenikItems(result));
                        cenikListAdapter.setItems(cenikItems);

                        String searchFilterText = binding.etSearchCenik.getText() != null ? binding.etSearchCenik.getText().toString() : "";
                        if (!searchFilterText.isEmpty()) {
                            cenikListAdapter.filter(searchFilterText);
                        }

                        String infoMsg = "Naložen cenik: " + cenikItems.size() + " artiklov (CENA0CENIK=" + Globals.getInstance().isCena0Cenik() + ", abecedno ureditvijo)";
                        Toast.makeText(requireContext(), infoMsg, Toast.LENGTH_LONG).show();
                        Globals.getInstance().vpisiKronologijo("getCenik: " + infoMsg);

                        displayQuickKeysForGroup(currentSkupinaId);
                    } else {
                        loadFallbackCenikItems();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Napaka pri nalaganju cenika iz SOAP", e);
                mainHandler.post(this::loadFallbackCenikItems);
            }
        });
    }

    private void loadFallbackCenikItems() {
        List<CenikListAdapter.CenikItem> rawFallback = new ArrayList<>();
        rawFallback.add(new CenikListAdapter.CenikItem(2933, "VB7 A+, BELO GORIŠKA BRDA ČČĆŽŠĐ", "02933", new BigDecimal("22.00"), 1, 1.0));
        rawFallback.add(new CenikListAdapter.CenikItem(3744, "WHISKY GLENMORANGIE PORT FINISH", "03744", new BigDecimal("4.50"), 2, 0.03));
        rawFallback.add(new CenikListAdapter.CenikItem(1001, "COCA COLA 0.25", "00101", new BigDecimal("2.80"), 0, 1.0));
        rawFallback.add(new CenikListAdapter.CenikItem(1002, "KAVA ESPRESSO", "00102", new BigDecimal("1.80"), 0, 1.0));
        rawFallback.add(new CenikListAdapter.CenikItem(1003, "LAŠKO PIVO 0.5", "00103", new BigDecimal("3.20"), 1, 0.5));
        rawFallback.add(new CenikListAdapter.CenikItem(1004, "RAMSTEK 300G (PROSTI VNOS CENE)", "00104", new BigDecimal("-1.00"), 0, 1.0));
        rawFallback.add(new CenikListAdapter.CenikItem(1005, "VRAČILO / POPUST (NEGATIVNA CENA)", "00105", new BigDecimal("-2.00"), 0, 1.0));

        cenikItems.clear();
        cenikItems.addAll(processAndSortCenikItems(rawFallback));
        cenikListAdapter.setItems(cenikItems);
        Globals.getInstance().setCachedCenik(rawFallback);
        refreshOrderArticleNames();

        String msg = "Naložen privzeti rezervni cenik (" + cenikItems.size() + " artiklov)";
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        Globals.getInstance().vpisiKronologijo("getCenik: " + msg);
    }

    private List<CenikListAdapter.CenikItem> processAndSortCenikItems(List<CenikListAdapter.CenikItem> inputList) {
        List<CenikListAdapter.CenikItem> list = new ArrayList<>();
        if (inputList == null) return list;

        boolean allowCena0 = Globals.getInstance().isCena0Cenik();

        for (CenikListAdapter.CenikItem item : inputList) {
            if (item == null) continue;
            // STANDARD = 'SKRIJ' (NIMOZNAIZBIRA)
            if (item.niMoznaIzbira || "SKRIJ".equalsIgnoreCase(item.standard)) {
                continue;
            }
            item.updateCenaForCurrentState();
            // Preskoči artikle s ceno 0 če CENA0CENIK = false (default)
            if (!allowCena0 && item.cena != null && item.cena.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            list.add(item);
        }

        // Sortiranje po abecedi s slovenskim Collator-jem (razvrščanje Č, Š, Ž, Ć, Đ)
        try {
            java.text.Collator collator = java.text.Collator.getInstance(new Locale("sl", "SI"));
            collator.setStrength(java.text.Collator.PRIMARY);
            java.util.Collections.sort(list, (a, b) -> {
                String nameA = a.naziv != null ? a.naziv : "";
                String nameB = b.naziv != null ? b.naziv : "";
                return collator.compare(nameA, nameB);
            });
        } catch (Exception e) {
            java.util.Collections.sort(list, (a, b) -> {
                String nameA = a.naziv != null ? a.naziv : "";
                String nameB = b.naziv != null ? b.naziv : "";
                return nameA.compareToIgnoreCase(nameB);
            });
        }

        return list;
    }

    private void updateOrderSummary() {
        orderAdapter.setItems(orderItems);

        BigDecimal totalZnesek = BigDecimal.ZERO;
        for (NarociloItem item : orderItems) {
            totalZnesek = totalZnesek.add(item.getZnesek());
        }

        BigDecimal placano = (currentRacun != null && currentRacun.getPlacano() != null) ? currentRacun.getPlacano() : BigDecimal.ZERO;
        String zaklenjenRazlog = getZaklenjenRazlog();
        boolean zaklenjen = (zaklenjenRazlog != null);

        if (zaklenjen) {
            binding.tvMizaStatus.setTextColor(android.graphics.Color.RED);
            String oznaka = (currentRacun != null && currentRacun.isPlacan())
                    ? "[PLAČAN - ZAKLENJENO]"
                    : "[ZAKLJUČEN (" + zaklenjenRazlog + ")]";
            binding.tvMizaStatus.setText(String.format(Locale.getDefault(), "M: %s  -  Zn: %.2f / Pl: %.2f %s", activeMarker, totalZnesek, placano, oznaka));
            binding.btnBrisanje.setEnabled(false);
            binding.btnNarociPost.setEnabled(false);
        } else {
            binding.tvMizaStatus.setTextColor(android.graphics.Color.WHITE);
            binding.tvMizaStatus.setText(String.format(Locale.getDefault(), "M: %s  -  Zn: %.2f / Pl: %.2f", activeMarker, totalZnesek, placano));
            binding.btnBrisanje.setEnabled(true);
            binding.btnNarociPost.setEnabled(true);
        }
    }

    private int resolveNivo4Id(String naziv) {
        if (naziv == null || naziv.trim().isEmpty()) return 0;
        String clean = naziv.trim().toUpperCase(Locale.ROOT);

        // 1. Preveri v naloženih cenikItems
        if (cenikItems != null) {
            for (CenikListAdapter.CenikItem ci : cenikItems) {
                if (ci.naziv != null && ci.naziv.trim().equalsIgnoreCase(clean)) {
                    return ci.nivo4Id;
                }
            }
            for (CenikListAdapter.CenikItem ci : cenikItems) {
                if (ci.naziv != null && (ci.naziv.toUpperCase(Locale.ROOT).contains(clean) || clean.contains(ci.naziv.toUpperCase(Locale.ROOT)))) {
                    return ci.nivo4Id;
                }
            }
        }

        // 2. Preveri v allApiHitreTipke
        if (allApiHitreTipke != null) {
            for (HitraTipkaTp ht : allApiHitreTipke) {
                if (ht.getNaziv() != null && ht.getNaziv().trim().equalsIgnoreCase(clean)
                        && ht.getNivo4Id() != null && ht.getNivo4Id() > 0) {
                    return ht.getNivo4Id();
                }
            }
        }

        // 3. Fallback šifre za testiranje/rezervni način
        if (clean.contains("COCA COLA")) return 1001;
        if (clean.contains("KAVA")) return 1002;
        if (clean.contains("LAŠKO") || clean.contains("LASKO")) return 1003;
        if (clean.contains("RAMSTEK")) return 1004;
        if (clean.contains("VRAČILO") || clean.contains("VRACILO")) return 1005;
        if (clean.contains("VB7")) return 2933;
        if (clean.contains("WHISKY")) return 3744;
        if (clean.contains("UNION")) return 1006;
        if (clean.contains("RADLER")) return 1007;
        if (clean.contains("HEINEKEN")) return 1008;
        if (clean.contains("COCKTA")) return 1009;
        if (clean.contains("FANTA")) return 1010;
        if (clean.contains("VODA")) return 1011;
        if (clean.contains("RADENSKA")) return 1012;
        if (clean.contains("SOK")) return 1013;
        if (clean.contains("CAPPUCCINO")) return 1014;
        if (clean.contains("ČAJ") || clean.contains("CAJ")) return 1015;
        if (clean.contains("KAKAV")) return 1016;
        if (clean.contains("MALVAZIJA")) return 1017;
        if (clean.contains("REFOŠK") || clean.contains("REFOSK")) return 1018;
        if (clean.contains("RIZLING")) return 1019;
        if (clean.contains("CABERNET")) return 1020;
        if (clean.contains("JACK DANIELS")) return 1021;
        if (clean.contains("JÄGERMEISTER") || clean.contains("JAGERMEISTER")) return 1022;
        if (clean.contains("PELINKOVAC")) return 1023;
        if (clean.contains("BIFTEK")) return 1024;
        if (clean.contains("T-BONE")) return 1025;
        if (clean.contains("AMARO")) return 1026;
        if (clean.contains("PENINA")) return 1027;

        return 0;
    }

    private boolean isRocniVnosCeneDovoljen(String naziv, int paket) {
        if (paket == 2) return false;
        if (naziv != null && naziv.toUpperCase(Locale.ROOT).contains("KAVCIJA")) return false;
        Globals g = Globals.getInstance();
        return g.isVnosCeneZaVseIni() || g.isDovoljeno(si.ros.RosKasa.models.PraviceConsts.SVidiVsaNarocila);
    }

    private boolean isLestvicaNacinProdaje(int nacinProdaje) {
        return nacinProdaje == 1 || nacinProdaje == 2 || nacinProdaje == 3
                || nacinProdaje == 4 || nacinProdaje == 9 || nacinProdaje == 10;
    }

    private void handleQuickKeyBooking(QuickKeyAdapter.QuickKey key) {
        int targetNivo4Id = key.nivo4Id;
        if (targetNivo4Id <= 0) {
            targetNivo4Id = resolveNivo4Id(key.title);
        }

        CenikListAdapter.CenikItem matchedCenik = findCenikItem(targetNivo4Id, key.title);
        BigDecimal basePrice = (matchedCenik != null && matchedCenik.cena != null)
                ? matchedCenik.cena
                : BigDecimal.valueOf(key.price);
        int nacinProdaje = matchedCenik != null ? matchedCenik.nacinProdaje : key.nacinProdaje;
        double pomPolnjenje = matchedCenik != null ? matchedCenik.polnjenje : key.polnjenje;
        int paket = matchedCenik != null ? matchedCenik.paket : key.paket;
        double keyEp = (key.ep > 0) ? key.ep : 1.0;
        double keyKol = (key.kolicina > 0) ? key.kolicina : 1.0;
        final String dodatekOpis = (key.dodatekId != null && key.dodatekId > 0)
                ? Globals.getInstance().findDodatekTextById(key.dodatekId)
                : "";

        if (basePrice.compareTo(new BigDecimal("-1.00")) == 0 || basePrice.compareTo(new BigDecimal("-2.00")) == 0) {
            if (!isRocniVnosCeneDovoljen(key.title, paket)) {
                Toast.makeText(requireContext(), "Ročni vnos cene ni dovoljen!", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean allowNegative = basePrice.compareTo(new BigDecimal("-2.00")) == 0;
            final int finalNivo4Id = targetNivo4Id;
            final double finalEp = (nacinProdaje == 6) ? 1.0 : keyEp;
            VnosCeneDialog.show(requireContext(), key.title, allowNegative, new VnosCeneDialog.OnPriceEnteredListener() {
                @Override
                public void onPriceEntered(BigDecimal price) {
                    knjiziVNarocilo(finalNivo4Id, key.title, price, keyKol, finalEp, paket, dodatekOpis);
                }

                @Override
                public void onCancelled() {}
            });
            return;
        }

        // Če ima hitra tipka eksplicitno EP != 1, jo knjižimo neposredno; sicer za lestvične načine prodaje odpremo LestvicaPolnjenjaDialog
        if (isLestvicaNacinProdaje(nacinProdaje) && Math.abs(keyEp - 1.0) <= 1e-6) {
            final int finalNivo4Id = targetNivo4Id;
            final BigDecimal finalBasePrice = basePrice;
            LestvicaPolnjenjaDialog.show(requireContext(), key.title, nacinProdaje, pomPolnjenje, new LestvicaPolnjenjaDialog.OnScaleSelectedListener() {
                @Override
                public void onScaleSelected(double epScale) {
                    knjiziVNarocilo(finalNivo4Id, key.title, finalBasePrice, keyKol, epScale, paket, dodatekOpis);
                }

                @Override
                public void onCancelled() {}
            });
            return;
        }

        double finalEp = (nacinProdaje == 6) ? 1.0 : keyEp;
        knjiziVNarocilo(targetNivo4Id, key.title, basePrice, keyKol, finalEp, paket, dodatekOpis);
    }

    private void handleCenikItemBooking(CenikListAdapter.CenikItem item) {
        if (item == null) return;
        item.updateCenaForCurrentState();

        if (item.cena.compareTo(new BigDecimal("-1.00")) == 0 || item.cena.compareTo(new BigDecimal("-2.00")) == 0) {
            if (!isRocniVnosCeneDovoljen(item.naziv, item.paket)) {
                Toast.makeText(requireContext(), "Ročni vnos cene ni dovoljen!", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean allowNeg = item.cena.compareTo(new BigDecimal("-2.00")) == 0;
            VnosCeneDialog.show(requireContext(), item.naziv, allowNeg, new VnosCeneDialog.OnPriceEnteredListener() {
                @Override
                public void onPriceEntered(BigDecimal price) {
                    knjiziVNarocilo(item.nivo4Id, item.naziv, price, 1.0, 1.0, item.paket, "");
                }

                @Override
                public void onCancelled() {}
            });
            return;
        }

        if (isLestvicaNacinProdaje(item.nacinProdaje)) {
            LestvicaPolnjenjaDialog.show(requireContext(), item.getFormattedNaziv(), item.nacinProdaje, item.polnjenje, new LestvicaPolnjenjaDialog.OnScaleSelectedListener() {
                @Override
                public void onScaleSelected(double epScale) {
                    knjiziVNarocilo(item.nivo4Id, item.naziv, item.cena, 1.0, epScale, item.paket, "");
                }

                @Override
                public void onCancelled() {}
            });
            return;
        }

        knjiziVNarocilo(item.nivo4Id, item.naziv, item.cena, 1.0, 1.0, item.paket, "");
    }

    private void knjiziVNarocilo(int nivo4Id, String naziv, BigDecimal cena, double kolicina, double ep, int paket) {
        knjiziVNarocilo(nivo4Id, naziv, cena, kolicina, ep, paket, "");
    }

    private void knjiziVNarocilo(int nivo4Id, String naziv, BigDecimal cena, double kolicina, double ep, int paket, String dodatniOpis) {
        if (naziv == null || naziv.trim().isEmpty()) return;

        if (isRacunZaklenjen()) {
            Toast.makeText(requireContext(), "Račun je zaklenjen! Urejanje / dodajanje artiklov ni dovoljeno.", Toast.LENGTH_LONG).show();
            return;
        }

        CenikListAdapter.CenikItem ci = findCenikItem(nivo4Id, naziv);
        if (ci != null && ci.nivo4Id > 0) {
            nivo4Id = ci.nivo4Id;
        }

        if (nivo4Id <= 0) {
            nivo4Id = resolveNivo4Id(naziv);
        }

        if (nivo4Id <= 0) {
            nivo4Id = 315100008; // default fallback so server never receives NULL
        }

        if (currentRacun == null) {
            initNewRacun();
        }

        Globals g = Globals.getInstance();

        // DNCENIK: če ima račun DN_ID, prepiši ceno iz CENIKVRCENE
        BigDecimal dnCena = g.findDnCena(currentRacun.getDnId(), nivo4Id);
        if (dnCena != null) {
            cena = dnCena;
        }

        if (cena.compareTo(BigDecimal.ZERO) == 0 && !g.isCena0Dovoljena()) {
            Toast.makeText(requireContext(), "Cena 0,00 EUR ni dovoljena!", Toast.LENGTH_SHORT).show();
            return;
        }

        int nacinProdaje = ci != null ? ci.nacinProdaje : 0;
        double polnjenje = ci != null ? ci.polnjenje : 1.0;
        int em = ci != null ? ci.em : 1;

        // Pravila NACIN_PRODAJE: 6 = kos (prisili EP = 1, celo število), 5 = kg (EP >= 0.01), EP < 1 -> cela števila (razen 5, 9)
        if (nacinProdaje == 6) {
            ep = 1.0;
            kolicina = Math.max(1.0, Math.rint(kolicina));
        } else if (nacinProdaje == 5 && ep < 0.01) {
            ep = 0.01;
        }
        if (ep < 1.0 && nacinProdaje != 5 && nacinProdaje != 9) {
            kolicina = Math.max(1.0, Math.rint(kolicina));
        }

        // Formula CenaPolnjenje (ko je ep != 1 in polnjenje != 0)
        cena = g.izracunajCenoPolnjenje(cena, ep, polnjenje, em);

        int tarifaId = (ci != null && ci.tarifaId != 0) ? ci.tarifaId : 40;
        double davekProc = (ci != null && ci.davekProc != 0) ? ci.davekProc : 22.0;
        if (ci != null && ci.tarifaId > 0) {
            si.ros.RosKasa.models.TarifaTp t = g.najdiTarifoZaId(ci.tarifaId);
            if (t != null && t.getDavekProc() != null) {
                davekProc = t.getDavekProc().doubleValue();
            }
        }
        int nivo1Id = (ci != null && ci.nivo1Id != 0) ? ci.nivo1Id : 1;
        int tocilnicaId = prefs.getTocilnicaId() > 0 ? prefs.getTocilnicaId() : (g.getTocilnicaId() != null && g.getTocilnicaId() > 0 ? g.getTocilnicaId() : 0);
        int kuhinjaId = prefs.getKuhinjaId() > 0 ? prefs.getKuhinjaId() : (g.getKuhinjaId() != null && g.getKuhinjaId() > 0 ? g.getKuhinjaId() : 512600);
        // IZVOR_STRM_ID: iz cenika; če 0 -> KUHINJA_ID za NIVO1_ID == 2 (hrana), sicer TOCILNICA_ID
        int izvorStrmId = (ci != null && ci.izvorStrmId > 0)
                ? ci.izvorStrmId
                : ((nivo1Id == 2 && kuhinjaId > 0) ? kuhinjaId : tocilnicaId);
        int izvorPrihodekId = izvorStrmId;
        int mobileId = 1;
        try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
        int posId = prefs.getfPosId() > 0 ? prefs.getfPosId() : (g.getfPosId() != null && g.getfPosId() > 0 ? g.getfPosId() : mobileId);
        int natakarId = g.getTekocaOsebaId() > 0 ? g.getTekocaOsebaId() : (currentRacun.getKasiral() != null && currentRacun.getKasiral() > 0 ? currentRacun.getKasiral() : 0);
        int cenikId = (ci != null && ci.cenikId > 0) ? ci.cenikId : 0;
        int izpisNarocila = (ci != null) ? ci.izpisNarocila : 0;
        boolean neNarocajFlag = g.izracunajNeNarocaj(izpisNarocila);

        BigDecimal vr = cena.multiply(BigDecimal.valueOf(kolicina * ep)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal zd = BigDecimal.ZERO;
        if (vr.compareTo(BigDecimal.ZERO) != 0 && davekProc > 0) {
            zd = vr.multiply(BigDecimal.valueOf(davekProc)).divide(BigDecimal.valueOf(100.0 + davekProc), 4, RoundingMode.HALF_UP);
        }

        // Zabeleži naziv artikla v globalni predpomnilnik za NIVO4_ID
        g.registerNazivForNivo4(nivo4Id, naziv);

        // Določi naslednji negativni POZICIJA_ID za novo pozicijo (Delphi stil: -1, -2, -3...)
        int nextPozId = -1;
        if (currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p.getPozicijaId() <= nextPozId) {
                    nextPozId = p.getPozicijaId() - 1;
                }
            }
        }

        boolean isPaket2Item = (paket == 2 || (ci != null && ci.paket == 2));
        if (isPaket2Item) {
            List<CenikVrVrTp> available = g.findCenikVrVrByPaketNivo4Id(nivo4Id);
            if (available != null && !available.isEmpty()) {
                final int fNivo4Id = nivo4Id;
                final String fNaziv = naziv;
                final BigDecimal fCena = cena;
                final double fKol = kolicina;
                final int fNextPozId = nextPozId;
                final int fTarifaId = tarifaId;
                final double fDavekProc = davekProc;
                final int fNivo1Id = nivo1Id;
                final int fTocId = tocilnicaId;
                final int fKuhId = kuhinjaId;
                final int fPosId = posId;
                final int fNatakarId = natakarId;
                final int fCenikId = cenikId;
                final int fIzpisNar = izpisNarocila;
                final String fDodatniOpis = dodatniOpis;
                PaketMixMatchDialog.show(requireContext(), naziv, cena, available, new PaketMixMatchDialog.OnMixMatchSelectedListener() {
                    @Override
                    public void onComponentsSelected(List<CenikVrVrTp> selectedComponents) {
                        knjiziPaketKomponente(fNivo4Id, fNaziv, fCena, fKol, fNextPozId, fTarifaId, fDavekProc, fNivo1Id, fTocId, fKuhId, fPosId, fNatakarId, fCenikId, fIzpisNar, fDodatniOpis, selectedComponents);
                    }

                    @Override
                    public void onCancelled() {}
                });
                return;
            }
        }

        boolean isPaketItem = (paket == 1 || (ci != null && ci.paket == 1));
        List<CenikVrVrTp> components = isPaketItem ? g.findCenikVrVrByPaketNivo4Id(nivo4Id) : null;

        if (isPaketItem && components != null && !components.isEmpty()) {
            knjiziPaketKomponente(nivo4Id, naziv, cena, kolicina, nextPozId, tarifaId, davekProc, nivo1Id, tocilnicaId, kuhinjaId, posId, natakarId, cenikId, izpisNarocila, dodatniOpis, components);
            return;
        }

        // Združevanje vrstic: združi le, če ni NOVAVRSTICANAROCILA, nima DODATNI_OPIS in nima HOD
        String cleanOpomba = dodatniOpis != null ? dodatniOpis.trim() : "";
        String tekociHod = g.getTekociHod() != null ? g.getTekociHod().trim() : "";
        boolean allowMerge = !g.isNovaVrsticaNarocila() && cleanOpomba.isEmpty() && tekociHod.isEmpty();

        boolean merged = false;
        if (allowMerge && currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                boolean pHasOpomba = p.getDodatniOpis() != null && !p.getDodatniOpis().trim().isEmpty();
                boolean pHasHod = p.getHod() != null && !p.getHod().trim().isEmpty();
                boolean pIsPaket = p.getPaketDistinct() != null && p.getPaketDistinct() > 0;
                if (!p.isRowDeleted() && p.getPozicijaId() < 0
                        && !pIsPaket && !pHasOpomba && !pHasHod
                        && p.getNivo4Id() != null && p.getNivo4Id() == nivo4Id
                        && p.getCena().compareTo(cena) == 0
                        && p.getEnotaProdajeId() != null && p.getEnotaProdajeId().compareTo(BigDecimal.valueOf(ep)) == 0) {
                    double novaKol = p.getKolicina() + kolicina;
                    p.setKolicina(novaKol);
                    BigDecimal novaVr = cena.multiply(BigDecimal.valueOf(novaKol * ep)).setScale(2, RoundingMode.HALF_UP);
                    p.setZnesek(novaVr);
                    if (davekProc > 0) {
                        p.setZnesekDavka(novaVr.multiply(BigDecimal.valueOf(davekProc)).divide(BigDecimal.valueOf(100.0 + davekProc), 4, RoundingMode.HALF_UP));
                    }
                    merged = true;
                    break;
                }
            }
        }

        if (!merged) {
            int targetRacunId = currentRacun.getRacunId() <= 0 ? -1 : currentRacun.getRacunId();
            PozicijaTp novaPozicija = new PozicijaTp(nextPozId, targetRacunId, nivo4Id, naziv, cena, kolicina);
            novaPozicija.setEnotaProdajeId(BigDecimal.valueOf(ep));
            novaPozicija.setTarifaId(tarifaId);
            novaPozicija.setStopnjaDavka(davekProc);
            novaPozicija.setZnesek(vr);
            novaPozicija.setZnesekDavka(zd);
            novaPozicija.setIzvorStrmId(izvorStrmId);
            novaPozicija.setIzvorPrihodekId(izvorPrihodekId);
            novaPozicija.setNatakarId(natakarId);
            novaPozicija.setTocilnicaId(tocilnicaId);
            novaPozicija.setKuhinjaId(kuhinjaId);
            novaPozicija.setPosId(posId);
            novaPozicija.setCenikId(cenikId);
            novaPozicija.setStatus(BigDecimal.ZERO);
            novaPozicija.setCenaNabavna(BigDecimal.ZERO);
            novaPozicija.setZnesekPopust(BigDecimal.ZERO);
            novaPozicija.setLojalnostPopust(BigDecimal.ZERO);
            novaPozicija.setZnesekLojalnost(BigDecimal.ZERO);
            novaPozicija.setPaketKol(BigDecimal.ONE);
            novaPozicija.setPaketNivo4Id(0);
            novaPozicija.setPaketDistinct(0);
            novaPozicija.setStatusPoz(0);
            novaPozicija.setNarociloPoslano(0);
            novaPozicija.setVerzijaZapisa(0);
            novaPozicija.setRowDeleted(false);
            novaPozicija.setNeNarocaj(neNarocajFlag);
            if (!cleanOpomba.isEmpty()) {
                novaPozicija.setDodatniOpis(cleanOpomba.length() > 240 ? cleanOpomba.substring(0, 240) : cleanOpomba);
            }
            if (!tekociHod.isEmpty()) {
                novaPozicija.setHod(tekociHod);
            }

            currentRacun.getRacPozic().add(novaPozicija);
        }

        hasUnsavedChanges = true;
        currentRacun.posodobiZnesekIzNarocila();
        currentRacun.preracunajVsote();
        // Vedno 100% sinhroniziraj orderItems iz racPozic
        populateOrderItemsFromCurrentRacun();
        updateOrderSummary();
        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Knjiženo: " + naziv + " (" + String.format(Locale.getDefault(), "%.2f €", cena) + ")", Toast.LENGTH_SHORT).show();
    }

    private void knjiziPaketKomponente(int nivo4Id, String naziv, BigDecimal cena, double kolicina,
                                       int nextPozId, int tarifaId, double davekProc, int nivo1Id,
                                       int tocilnicaId, int kuhinjaId, int posId, int natakarId, int cenikId,
                                       int izpisNarocila, String dodatniOpis, List<CenikVrVrTp> components) {
        if (currentRacun == null || components == null || components.isEmpty()) return;
        Globals g = Globals.getInstance();
        int pompaketdistinct = new java.util.Random().nextInt(90000) + 10000;
        BigDecimal paketCenaPaketa = cena;
        BigDecimal compSum = BigDecimal.ZERO;
        BigDecimal[] baseCompCene = new BigDecimal[components.size()];
        for (int i = 0; i < components.size(); i++) {
            CenikVrVrTp comp = components.get(i);
            BigDecimal compBase = g.findDnCena(currentRacun.getDnId(), comp.getNivo4Id());
            if (compBase == null) {
                if (g.isCena2Aktivna()) {
                    if (g.getModelCena2() == 2) {
                        compBase = comp.getCena2() != null ? comp.getCena2() : BigDecimal.ZERO;
                    } else {
                        compBase = (comp.getCena2() != null && comp.getCena2().compareTo(BigDecimal.ZERO) != 0)
                                ? comp.getCena2()
                                : (comp.getCena1() != null ? comp.getCena1() : BigDecimal.ZERO);
                    }
                } else {
                    compBase = comp.getCena1() != null ? comp.getCena1() : BigDecimal.ZERO;
                }
            }
            baseCompCene[i] = compBase;
            compSum = compSum.add(compBase.multiply(BigDecimal.valueOf(comp.getKolicina())));
        }

        BigDecimal faktor = BigDecimal.ONE;
        if (compSum.compareTo(BigDecimal.ZERO) > 0 && paketCenaPaketa.compareTo(BigDecimal.ZERO) > 0) {
            faktor = paketCenaPaketa.divide(compSum, 6, RoundingMode.HALF_UP);
        }

        BigDecimal tekocePaketVsota = BigDecimal.ZERO;
        int targetRacunId = currentRacun.getRacunId() <= 0 ? -1 : currentRacun.getRacunId();

        for (int i = 0; i < components.size(); i++) {
            CenikVrVrTp comp = components.get(i);
            int compNivo4Id = comp.getNivo4Id();
            CenikListAdapter.CenikItem compCi = findCenikItemByNivo4Id(compNivo4Id);
            String compNaziv = g.findNazivByNivo4Id(compNivo4Id);
            if (compNaziv == null || compNaziv.trim().isEmpty()) {
                compNaziv = "Komponenta #" + compNivo4Id;
            }

            BigDecimal compCena = baseCompCene[i].multiply(faktor).setScale(2, RoundingMode.HALF_UP);
            BigDecimal compRowForOne = compCena.multiply(BigDecimal.valueOf(comp.getKolicina())).setScale(2, RoundingMode.HALF_UP);
            tekocePaketVsota = tekocePaketVsota.add(compRowForOne);

            if (i == components.size() - 1 && faktor.compareTo(BigDecimal.ONE) != 0) {
                BigDecimal razlika = paketCenaPaketa.subtract(tekocePaketVsota);
                if (razlika.compareTo(BigDecimal.ZERO) != 0 && comp.getKolicina() > 0) {
                    BigDecimal deltaPerUnit = razlika.divide(BigDecimal.valueOf(comp.getKolicina()), 4, RoundingMode.HALF_UP);
                    compCena = compCena.add(deltaPerUnit);
                }
            }

            double compKol = comp.getKolicina() * kolicina;
            BigDecimal compZnesek = compCena.multiply(BigDecimal.valueOf(compKol)).setScale(2, RoundingMode.HALF_UP);

            int compTarifa = comp.getTarifaId() > 0
                    ? comp.getTarifaId()
                    : (compCi != null && compCi.tarifaId > 0 ? compCi.tarifaId : tarifaId);
            double compDavek = davekProc;
            si.ros.RosKasa.models.TarifaTp compTarifaObj = g.najdiTarifoZaId(compTarifa);
            if (compTarifaObj != null && compTarifaObj.getDavekProc() != null) {
                compDavek = compTarifaObj.getDavekProc().doubleValue();
            } else if (compCi != null && compCi.davekProc > 0) {
                compDavek = compCi.davekProc;
            }

            BigDecimal compZd = BigDecimal.ZERO;
            if (compZnesek.compareTo(BigDecimal.ZERO) != 0 && compDavek > 0) {
                compZd = compZnesek.multiply(BigDecimal.valueOf(compDavek)).divide(BigDecimal.valueOf(100.0 + compDavek), 4, RoundingMode.HALF_UP);
            }

            int compNivo1 = comp.getNivo1Id() > 0 ? comp.getNivo1Id() : (compCi != null ? compCi.nivo1Id : nivo1Id);
            int compIzvorStrm = comp.getIzvorStrmId() > 0
                    ? comp.getIzvorStrmId()
                    : ((compNivo1 == 2 && kuhinjaId > 0) ? kuhinjaId : tocilnicaId);
            int compIzpisNar = compCi != null ? compCi.izpisNarocila : izpisNarocila;

            int cPozId = nextPozId - i;
            PozicijaTp poz = new PozicijaTp(cPozId, targetRacunId, compNivo4Id, compNaziv, compCena, compKol);
            poz.setEnotaProdajeId(BigDecimal.ONE);
            poz.setTarifaId(compTarifa);
            poz.setStopnjaDavka(compDavek);
            poz.setZnesek(compZnesek);
            poz.setZnesekDavka(compZd);
            poz.setIzvorStrmId(compIzvorStrm);
            poz.setIzvorPrihodekId(comp.getIzvorPrihodekId() > 0 ? comp.getIzvorPrihodekId() : compIzvorStrm);
            poz.setNatakarId(natakarId);
            poz.setTocilnicaId(tocilnicaId);
            poz.setKuhinjaId(kuhinjaId);
            poz.setPosId(posId);
            poz.setCenikId(comp.getCenikId() > 0 ? comp.getCenikId() : cenikId);
            poz.setStatus(BigDecimal.ZERO);
            poz.setCenaNabavna(BigDecimal.ZERO);
            poz.setZnesekPopust(BigDecimal.ZERO);
            poz.setLojalnostPopust(BigDecimal.ZERO);
            poz.setZnesekLojalnost(BigDecimal.ZERO);
            poz.setPaketKol(BigDecimal.valueOf(kolicina));
            poz.setPaketNivo4Id(nivo4Id);
            poz.setPaketDistinct(pompaketdistinct);
            poz.setStatusPoz(0);
            poz.setNarociloPoslano(0);
            poz.setVerzijaZapisa(0);
            poz.setRowDeleted(false);
            poz.setNeNarocaj(g.izracunajNeNarocaj(compIzpisNar));
            if (dodatniOpis != null && !dodatniOpis.trim().isEmpty()) {
                poz.setDodatniOpis(dodatniOpis.trim());
            }
            if (g.getTekociHod() != null && !g.getTekociHod().isEmpty()) {
                poz.setHod(g.getTekociHod());
            }

            currentRacun.getRacPozic().add(poz);
        }

        hasUnsavedChanges = true;
        currentRacun.posodobiZnesekIzNarocila();
        currentRacun.preracunajVsote();
        populateOrderItemsFromCurrentRacun();
        updateOrderSummary();
        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Knjižen paket: " + naziv + " (" + String.format(Locale.getDefault(), "%.2f €", cena) + ")", Toast.LENGTH_SHORT).show();
    }

    private void loadHitreTipkeFromApi() {
        if (Globals.getInstance().hasCachedHitreTipke()) {
            allApiHitreTipke.clear();
            allApiHitreTipke.addAll(Globals.getInstance().getCachedHitreTipke());
            currentSkupinaId = 1;
            skupinaHistory.clear();
            displayQuickKeysForGroup(1);
            return;
        }

        int tipkePosId = prefs.getTipkePosId();

        if (!prefs.isRegistered()) {
            showFallbackCategoryKeys();
            return;
        }

        executor.execute(() -> {
            try {
                List<HitraTipkaTp> result = RosKasaSoapClient.getHitreTipke(
                        prefs.getServerUrl(),
                        prefs.getToken(),
                        tipkePosId
                );

                mainHandler.post(() -> {
                    if (result != null && !result.isEmpty()) {
                        Globals.getInstance().setCachedHitreTipke(result);
                        refreshOrderArticleNames();
                        allApiHitreTipke.clear();
                        allApiHitreTipke.addAll(result);
                        currentSkupinaId = 1;
                        skupinaHistory.clear();
                        displayQuickKeysForGroup(1);
                    } else {
                        showFallbackCategoryKeys();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(this::showFallbackCategoryKeys);
            }
        });
    }

    private CenikListAdapter.CenikItem findCenikItem(int nivo4Id, String naziv) {
        List<CenikListAdapter.CenikItem> pool = new ArrayList<>();
        if (cenikItems != null) pool.addAll(cenikItems);
        if (Globals.getInstance().hasCachedCenik()) {
            for (CenikListAdapter.CenikItem ci : Globals.getInstance().getCachedCenik()) {
                if (!pool.contains(ci)) pool.add(ci);
            }
        }

        if (nivo4Id > 0) {
            for (CenikListAdapter.CenikItem ci : pool) {
                if (ci.nivo4Id == nivo4Id) return ci;
            }
        }

        if (naziv != null && !naziv.trim().isEmpty()) {
            String clean = naziv.trim().toUpperCase(Locale.ROOT);
            for (CenikListAdapter.CenikItem ci : pool) {
                if (ci.naziv != null && ci.naziv.trim().equalsIgnoreCase(clean)) return ci;
            }
            for (CenikListAdapter.CenikItem ci : pool) {
                if (ci.naziv != null && (ci.naziv.toUpperCase(Locale.ROOT).contains(clean) || clean.contains(ci.naziv.toUpperCase(Locale.ROOT)))) {
                    return ci;
                }
            }
        }
        return null;
    }

    private CenikListAdapter.CenikItem findCenikItemByNivo4Id(int nivo4Id) {
        return findCenikItem(nivo4Id, null);
    }

    private void displayQuickKeysForGroup(int skupinaId) {
        if (allApiHitreTipke.isEmpty()) {
            showFallbackCategoryKeys();
            return;
        }

        int maxKeys = DeviceLayoutHelper.getQuickKeysMaxCount(requireContext());

        List<QuickKeyAdapter.QuickKey> keys = new ArrayList<>();
        List<QuickKeyAdapter.QuickKey> groupKeys = new ArrayList<>();

        if (skupinaId != 1) {
            QuickKeyAdapter.QuickKey backKey = new QuickKeyAdapter.QuickKey("[ NAZAJ ]", false, true, "NAZAJ", 0);
            keys.add(backKey);
        }

        for (HitraTipkaTp item : allApiHitreTipke) {
            if (item.getSkupinaId() != null && item.getSkupinaId() == skupinaId) {
                String rawNaziv = item.getNaziv() != null ? item.getNaziv().trim() : "";
                // Če imamo že rdečo tipko [ NAZAJ ] (skupinaId != 1), preskočimo podvojeno tipko "<<<" iz šifranta
                if (skupinaId != 1 && ("<<<".equals(rawNaziv) || "NAZAJ".equalsIgnoreCase(rawNaziv))) {
                    continue;
                }
                boolean isCategory = item.getNivo4Id() != null && item.getNivo4Id() < 0;
                Integer targetGroupId = isCategory ? Math.abs(item.getNivo4Id()) : null;
                int nivo4Id = item.getNivo4Id() != null ? item.getNivo4Id() : 0;

                double price = 0.0;
                CenikListAdapter.CenikItem matchedCenik = !isCategory ? findCenikItemByNivo4Id(nivo4Id) : null;
                if (matchedCenik != null) {
                    matchedCenik.updateCenaForCurrentState();
                    if (matchedCenik.cena != null) {
                        price = matchedCenik.cena.doubleValue();
                    }
                }

                QuickKeyAdapter.QuickKey qk = new QuickKeyAdapter.QuickKey(
                        item.getNaziv() != null ? item.getNaziv() : "",
                        isCategory,
                        false,
                        null,
                        price
                );
                qk.categoryTargetId = targetGroupId;
                qk.tipkaId = item.getTipkaId();
                qk.nivo4Id = nivo4Id;
                qk.ep = (item.getEnotaProdaje() != null && item.getEnotaProdaje().compareTo(BigDecimal.ZERO) > 0)
                        ? item.getEnotaProdaje().doubleValue()
                        : 1.0;
                qk.barva = item.getBarva();
                qk.dodatekId = item.getDodatekId();
                qk.kolicina = (item.getKolicina() != null && item.getKolicina() > 0) ? item.getKolicina() : 1;
                if (matchedCenik != null) {
                    qk.nacinProdaje = matchedCenik.nacinProdaje;
                    qk.polnjenje = matchedCenik.polnjenje;
                    qk.paket = matchedCenik.paket;
                }
                groupKeys.add(qk);
            }
        }

        // Sortiranje hitrih tipk po TIPKA_ID (naraščajoče, enako kot Delphi tblHitreTipke.IndexFieldNames := 'TIPKA_ID')
        groupKeys.sort((a, b) -> Integer.compare(a.tipkaId, b.tipkaId));

        for (QuickKeyAdapter.QuickKey qk : groupKeys) {
            if (keys.size() >= maxKeys) break;
            keys.add(qk);
        }

        // Prazna podskupina (skupinaId != 1) prikaže samo gumb [ NAZAJ ] (ne vseh tipk!)
        quickKeyAdapter.setMaxKeys(maxKeys);
        quickKeyAdapter.setKeys(keys);
    }

    private void showFallbackCategoryKeys() {
        currentSkupinaId = 1;
        skupinaHistory.clear();

        List<QuickKeyAdapter.QuickKey> keys = new ArrayList<>();

        keys.add(new QuickKeyAdapter.QuickKey("BREZALKOHOLNE", true, false, null, 0));
        keys.add(new QuickKeyAdapter.QuickKey("PIVO", true, false, null, 0));
        keys.add(new QuickKeyAdapter.QuickKey("TOPLI NAPITKI", true, false, null, 0));
        keys.add(new QuickKeyAdapter.QuickKey("VINA", true, false, null, 0));
        keys.add(new QuickKeyAdapter.QuickKey("ŽGANE PIJAČE", true, false, null, 0));

        addFallbackArticleKey(keys, "PGA", 3.50);
        addFallbackArticleKey(keys, "SKUP test", 5.00);
        addFallbackArticleKey(keys, "PENINA RADGO", 18.00);
        addFallbackArticleKey(keys, "COCA COLA 0.25", 2.80);
        keys.add(new QuickKeyAdapter.QuickKey("STEAKI", true, false, null, 0));

        addFallbackArticleKey(keys, "ČAJ", 2.20);
        addFallbackArticleKey(keys, "AMARO", 3.00);
        addFallbackArticleKey(keys, "8% DDV PAVŠAL", 0.00);

        quickKeyAdapter.setKeys(keys);
    }

    private void addFallbackArticleKey(List<QuickKeyAdapter.QuickKey> keys, String title, double price) {
        QuickKeyAdapter.QuickKey qk = new QuickKeyAdapter.QuickKey(title, false, false, null, price);
        qk.nivo4Id = resolveNivo4Id(title);
        keys.add(qk);
    }

    private void addFallbackArticleKey(List<QuickKeyAdapter.QuickKey> keys, String title, String category, double price) {
        QuickKeyAdapter.QuickKey qk = new QuickKeyAdapter.QuickKey(title, false, false, category, price);
        qk.nivo4Id = resolveNivo4Id(title);
        keys.add(qk);
    }

    private void showArticlesForCategoryFallback(String category) {
        List<QuickKeyAdapter.QuickKey> keys = new ArrayList<>();
        keys.add(new QuickKeyAdapter.QuickKey("[ NAZAJ ]", false, true, category, 0));

        switch (category) {
            case "PIVO":
                addFallbackArticleKey(keys, "GUINNES 0.33", category, 4.00);
                addFallbackArticleKey(keys, "LAŠKO 0.5", category, 3.20);
                addFallbackArticleKey(keys, "UNION 0.5", category, 3.20);
                addFallbackArticleKey(keys, "RADLER 0.5", category, 3.00);
                addFallbackArticleKey(keys, "HEINEKEN 0.33", category, 3.50);
                addFallbackArticleKey(keys, "BEZALKOHOLNO PIVO", category, 3.00);
                break;

            case "BREZALKOHOLNE":
                addFallbackArticleKey(keys, "COCA COLA 0.25", category, 2.80);
                addFallbackArticleKey(keys, "COCKTA 0.25", category, 2.80);
                addFallbackArticleKey(keys, "FANTA 0.25", category, 2.80);
                addFallbackArticleKey(keys, "VODA 0.5", category, 2.00);
                addFallbackArticleKey(keys, "RADENSKA 0.25", category, 2.20);
                addFallbackArticleKey(keys, "JABOLČNI SOK", category, 2.60);
                break;

            case "TOPLI NAPITKI":
                addFallbackArticleKey(keys, "KAVA ESPRESSO", category, 1.80);
                addFallbackArticleKey(keys, "KAVA Z MLEKOM", category, 2.20);
                addFallbackArticleKey(keys, "CAPPUCCINO", category, 2.40);
                addFallbackArticleKey(keys, "ČAJ Z MEDOM", category, 2.50);
                addFallbackArticleKey(keys, "KAKAV", category, 2.60);
                break;

            case "VINA":
                addFallbackArticleKey(keys, "MALVAZIJA 0.1", category, 2.20);
                addFallbackArticleKey(keys, "REFOŠK 0.1", category, 2.20);
                addFallbackArticleKey(keys, "RENSKI RIZLING", category, 3.50);
                addFallbackArticleKey(keys, "CABERNET", category, 3.80);
                break;

            case "ŽGANE PIJAČE":
                addFallbackArticleKey(keys, "WHISKY GLENMORANGIE", category, 4.50);
                addFallbackArticleKey(keys, "JACK DANIELS", category, 4.00);
                addFallbackArticleKey(keys, "JÄGERMEISTER", category, 3.50);
                addFallbackArticleKey(keys, "PELINKOVAC", category, 3.00);
                break;

            default:
                addFallbackArticleKey(keys, "BIFTEK 250G", category, 22.00);
                addFallbackArticleKey(keys, "RAMSTEK 300G", category, 18.00);
                addFallbackArticleKey(keys, "T-BONE 500G", category, 28.00);
                break;
        }

        quickKeyAdapter.setKeys(keys);
    }

    private void setupNavigationButtons() {
        binding.btnNavMize.setOnClickListener(v -> checkUnpostedAndNavigate(new MizeFragment()));

        binding.btnNavRacuni.setOnClickListener(v -> checkUnpostedAndNavigate(new RacuniFragment()));

        binding.btnNavPlacila.setOnClickListener(v -> checkUnpostedAndNavigate(new PlacilaFragment()));

        binding.btnOdjava.setVisibility(Globals.getInstance().ispLogout() ? View.VISIBLE : View.INVISIBLE);
        binding.btnOdjava.setOnClickListener(v -> {
            if (hasUnpostedChanges()) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Neshranjeno naročilo")
                        .setMessage("Imate neshranjeno naročilo. Ali ga želite shraniti pred odjavo?")
                        .setPositiveButton("Shrani in odjavi", (dialog, which) -> {
                            Globals.getInstance().vpisiKronologijo("Odjava osebe (Shrani in odjavi)");
                            Globals.getInstance().posljiOfflineKronologijo();
                            Globals.getInstance().setTekocaOseba(null);
                            handlePostNarocilo(new LoginFragment());
                        })
                        .setNegativeButton("Zavrzi in odjavi", (dialog, which) -> {
                            Globals.getInstance().vpisiKronologijo("Odjava osebe (Zavrzi in odjavi)");
                            Globals.getInstance().posljiOfflineKronologijo();
                            Globals.getInstance().setTekocaOseba(null);
                            if (getActivity() instanceof MainActivity) {
                                ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
                            }
                        })
                        .setNeutralButton("Prekliči", null)
                        .show();
            } else {
                Globals.getInstance().vpisiKronologijo("Odjava osebe (Narocila)");
                Globals.getInstance().posljiOfflineKronologijo();
                Globals.getInstance().setTekocaOseba(null);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
                }
            }
        });

        // Gumb CENIK/TIPKE: preklaplja med prikazi (Mreža Hitrih Tipk <-> Seznam Cenika)
        binding.btnCenikTipke.setOnClickListener(v -> {
            // Zapri tipkovnico, če je odprta (npr. iz iskalnika cenika)
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                View focusView = requireActivity().getCurrentFocus();
                if (focusView == null) focusView = binding.etSearchCenik;
                imm.hideSoftInputFromWindow(focusView.getWindowToken(), 0);
            }
            binding.etSearchCenik.clearFocus();

            if (binding.llCenikContainer.getVisibility() == View.VISIBLE) {
                binding.llCenikContainer.setVisibility(View.GONE);
                binding.rvQuickKeys.setVisibility(View.VISIBLE);
                currentSkupinaId = 1;
                skupinaHistory.clear();
                displayQuickKeysForGroup(1);
                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Prikaz: Hitre tipke (Skupina 1)", Toast.LENGTH_SHORT).show();
            } else {
                binding.rvQuickKeys.setVisibility(View.GONE);
                binding.llCenikContainer.setVisibility(View.VISIBLE);
                binding.etSearchCenik.requestFocus();
                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Prikaz: Lista cenika", Toast.LENGTH_SHORT).show();
            }
        });

        // Gumb HOD-I: Odpre celozaslonski dialog za določitev hodov (FormHodi)
        binding.btnHodi.setOnClickListener(v -> {
            if (currentRacun == null || currentRacun.getRacPozic() == null || currentRacun.getRacPozic().isEmpty()) {
                Toast.makeText(requireContext(), "Na naročilu ni nobenega artikla!", Toast.LENGTH_SHORT).show();
                return;
            }
            HodiDialog dialog = HodiDialog.newInstance(currentRacun, updatedRacun -> {
                populateOrderItemsFromCurrentRacun();
                if (currentRacun.getRacunId() > 0) {
                    handlePostNarocilo();
                } else {
                    hasUnsavedChanges = true;
                    updateOrderSummary();
                }
            });
            dialog.show(getParentFragmentManager(), "HodiDialog");
        });

        // Gumb PREKLOPI CENIK: preklaplja tarifo med CENA 1 in CENA 2 (CENA2AKTIVNA)
        binding.btnPreklopiCenik.setOnClickListener(v -> {
            Globals g = Globals.getInstance();
            if (g.isCena2PreklopOff() || cena2LockedByHolidayOrWeekend) {
                Toast.makeText(requireContext(), "Preklop cenika ni dovoljen (CENA2PREKLOPOFF)!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!g.isLahkoPreklopiCenik()) {
                Toast.makeText(requireContext(), "Preklop cenika ni dovoljen!", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean novaCena2State = !g.isCena2Aktivna();
            g.setCena2Aktivna(novaCena2State);

            if (novaCena2State) {
                binding.btnPreklopiCenik.setText("CENA 2");
                g.vpisiKronologijo("Preklop cenika na cena2!");
                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Preklop cenika na CENA 2!", Toast.LENGTH_SHORT).show();
            } else {
                binding.btnPreklopiCenik.setText("PREKLOPI\nCENIK");
                g.vpisiKronologijo("Preklop cenika na cena!");
                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Preklop cenika na CENA 1!", Toast.LENGTH_SHORT).show();
            }

            updatePreklopiCenikButtonState();
            osveziCenePoPreklopu();
        });

        binding.btnBrisanje.setOnClickListener(v -> {
            if (isRacunZaklenjen()) {
                Toast.makeText(requireContext(), "Račun je zaključen! Brisanje postavk ni dovoljeno.", Toast.LENGTH_LONG).show();
                return;
            }
            if (orderItems.isEmpty()) {
                Toast.makeText(requireContext(), "Naročilo nima postavk za brisanje.", Toast.LENGTH_SHORT).show();
                return;
            }

            int selPos = orderAdapter.getSelectedPosition();
            if (selPos < 0 || selPos >= orderItems.size()) {
                selPos = orderItems.size() - 1;
            }

            NarociloItem itemToDelete = orderItems.get(selPos);
            if (itemToDelete.getPozicijaId() > 0) {
                if (!Globals.getInstance().isStornoPoslanegaNarocilaDovoljen()) {
                    Toast.makeText(requireContext(), "Nimate pravice za storniranje že poslanega artikla!", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            NarociloItem removed = orderItems.remove(selPos);
            if (currentRacun != null && currentRacun.getRacPozic() != null) {
                if (removed.getPaketDistinct() > 0) {
                    for (int i = currentRacun.getRacPozic().size() - 1; i >= 0; i--) {
                        PozicijaTp p = currentRacun.getRacPozic().get(i);
                        if (p != null && p.getPaketDistinct() != null && p.getPaketDistinct() == removed.getPaketDistinct()) {
                            if (p.getPozicijaId() <= 0) {
                                currentRacun.getRacPozic().remove(i);
                            } else {
                                p.setRowDeleted(true);
                            }
                        }
                    }
                } else {
                    for (int i = currentRacun.getRacPozic().size() - 1; i >= 0; i--) {
                        PozicijaTp p = currentRacun.getRacPozic().get(i);
                        if (p != null && !p.isRowDeleted()) {
                            boolean match = false;
                            if (removed.getPozicijaId() != 0 && p.getPozicijaId() != 0) {
                                match = (p.getPozicijaId() == removed.getPozicijaId());
                            } else if (p.getNaziv() != null && p.getNaziv().equals(removed.getNaziv())) {
                                match = true;
                            }

                            if (match) {
                                if (p.getPozicijaId() <= 0) {
                                    currentRacun.getRacPozic().remove(i);
                                } else {
                                    p.setRowDeleted(true);
                                }
                                break;
                            }
                        }
                    }
                }
                hasUnsavedChanges = true;
                currentRacun.posodobiZnesekIzNarocila();
                currentRacun.preracunajVsote();
            }

            int newSel = Math.min(selPos, orderItems.size() - 1);
            orderAdapter.setSelectedPosition(newSel);
            updateOrderSummary();
            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Izbrisan označen artikel: " + removed.getNaziv(), Toast.LENGTH_SHORT).show();
        });

        binding.btnNarociPost.setOnClickListener(v -> handlePostNarocilo());

        // Gumb POPUST / LOKATOR / HOD / DN (GumbNarocilaLevo)
        binding.btnPopust.setOnClickListener(v -> handleGumbNarocilaLevoClick());

        // Hitra plačila (spodnja vrstica) - Hotkeys iz MobIni (HOTKEY3, HOTKEY4, HOTKEY5, TIPKAGOTOVINA)
        updateQuickPaymentHotkeys();
    }

    private boolean hasUnpostedChanges() {
        if (currentRacun == null) return false;
        if (hasUnsavedChanges) return true;

        if (currentRacun.getRacunId() <= 0) {
            if (currentRacun.getRacPozic() != null) {
                for (PozicijaTp p : currentRacun.getRacPozic()) {
                    if (p != null && !p.isRowDeleted()) return true;
                }
            }
            return false;
        }

        if (currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null) {
                    if (p.getPozicijaId() < 0) return true;
                    if (p.isRowDeleted()) return true;
                }
            }
        }
        return false;
    }

    private void checkUnpostedAndNavigate(Fragment targetFragment) {
        if (hasUnpostedChanges()) {
            handlePostNarocilo(targetFragment);
        } else {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(targetFragment);
            }
        }
    }

    private void handlePostNarocilo() {
        handlePostNarocilo(null);
    }

    private void handlePostNarocilo(final Fragment targetFragmentOnSuccess) {
        long now = SystemClock.elapsedRealtime();
        if (isOperationInProgress || (now - lastActionTime < DEBOUNCE_DELAY)) {
            Log.d(TAG, "handlePostNarocilo: Zahteva ignorirana, operacija že poteka.");
            return;
        }
        lastActionTime = now;

        if (isRacunZaklenjen()) {
            Toast.makeText(requireContext(), "Račun je zaključen! Pošiljanje naročila ni dovoljeno.", Toast.LENGTH_LONG).show();
            if (targetFragmentOnSuccess != null && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(targetFragmentOnSuccess);
            }
            return;
        }

        if (currentRacun == null || currentRacun.getRacPozic() == null || currentRacun.getRacPozic().isEmpty()) {
            if (targetFragmentOnSuccess != null && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(targetFragmentOnSuccess);
            } else {
                Toast.makeText(requireContext(), "Naročilo je prazno! Dodajte artikle.", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        String ekranMsg = (targetFragmentOnSuccess != null)
                ? "Shranjevanje naročila..."
                : "Pošiljanje naročila na kuhinjo/šank...";
        disableEkran(ekranMsg);

        executor.execute(() -> {
            try {
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                int mobileId = 1;
                try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                // 1. Zgodnje preverjanje verzije zapisa (če račun že obstaja na strežniku)
                if (currentRacun.getRacunId() > 0) {
                    try {
                        int serverVer = RosKasaSoapClient.getVerzijaOfRacglava(serverUrl, token, currentRacun.getRacunId());
                        if (serverVer > currentRacun.getVerzijaZapisa()) {
                            throw new VersionConflictException(
                                    currentRacun.getRacunId(),
                                    currentRacun.getVerzijaZapisa(),
                                    serverVer,
                                    "Strežnik poroča višjo verzijo zapisa (" + serverVer + " > " + currentRacun.getVerzijaZapisa() + ")"
                            );
                        }
                    } catch (VersionConflictException vce) {
                        throw vce;
                    } catch (Exception e) {
                        Log.w(TAG, "getVerzijaOfRacglava opozorilo: " + e.getMessage());
                    }
                }

                // 2. Nastavitev parametrov za naročilo
                currentRacun.setStatus(1);
                currentRacun.setMarker(activeMarker);
                int fPosId = prefs.getfPosId() > 0 ? prefs.getfPosId() : (Globals.getInstance().getfPosId() != null && Globals.getInstance().getfPosId() > 0 ? Globals.getInstance().getfPosId() : 0);
                currentRacun.setfPosId(fPosId);
                int fPoslovniProstorId = Globals.getInstance().getfPoslovniProstorId() != null && Globals.getInstance().getfPoslovniProstorId() > 0 ? Globals.getInstance().getfPoslovniProstorId() : 0;
                currentRacun.setfPoslovniProstorId(fPoslovniProstorId);
                int tocId = Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0;
                currentRacun.setTocilnicaId(tocId);
                if (currentRacun.getKasiral() == null || currentRacun.getKasiral() <= 0) {
                    int tekocaOseba = Globals.getInstance().getTekocaOsebaId();
        currentRacun.setKasiral(tekocaOseba > 0 ? tekocaOseba : Globals.getInstance().getTekocaOsebaId());
                }
                if (currentRacun.getTipRacuna() == null || currentRacun.getTipRacuna() <= 0) {
                    currentRacun.setTipRacuna(1);
                }
                if (currentRacun.getStPogrinjkov() == null || currentRacun.getStPogrinjkov() <= 0) {
                    currentRacun.setStPogrinjkov(1);
                }
                if (currentRacun.getStKopij() == null) {
                    currentRacun.setStKopij(0);
                }
                currentRacun.preracunajVsote();

                // 3. SOAP klic setRacun
                GetRacunRsTp response = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);

                mainHandler.post(() -> handlePostRacunSuccess(response, targetFragmentOnSuccess));

            } catch (VersionConflictException vce) {
                mainHandler.post(() -> handleVersionConflict(vce));
            } catch (Exception e) {
                mainHandler.post(() -> handlePostError(e));
            }
        });
    }

    private void handlePostRacunSuccess(GetRacunRsTp response, Fragment targetFragmentOnSuccess) {
        enableEkran();
        if (response != null && response.getRacGlava() != null) {
            hasUnsavedChanges = false;
            currentRacun = response.getRacGlava();
            Globals.getInstance().setCurrentRacun(currentRacun);
            activeRacunId = currentRacun.getRacunId();
            prefs.setActiveRacunId(activeRacunId);

            populateOrderItemsFromCurrentRacun();

            // [DEBUG-TOAST] String infoMsg = "Naročilo uspešno oddano! (Račun #" + currentRacun.getRacunId() + ", Verzija " + currentRacun.getVerzijaZapisa() + ")";
            // [DEBUG-TOAST] Toast.makeText(requireContext(), infoMsg, Toast.LENGTH_SHORT).show();

            if (targetFragmentOnSuccess != null) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToFragment(targetFragmentOnSuccess);
                }
                return;
            }

            if (Globals.getInstance().ispLogoutPoNarocilu()) {
                if (Globals.getInstance().ispLogout() && getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
                } else if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToFragment(new MizeFragment());
                }
            }
        } else {
            Toast.makeText(requireContext(), "Naročilo poslano, vendar strežnik ni vrnil glave računa.", Toast.LENGTH_LONG).show();
        }
    }

    private void handleVersionConflict(VersionConflictException vce) {
        enableEkran();
        String msg = "KONFLIKT VERZIJE RAČUNA (V:" + vce.getLocalVerzija() + " vs S:" + vce.getServerVerzija() + ")!\nRačun je medtem spremenil drug natakar ali blagajna. Podatki se bodo osvežili s strežnika.";
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Konflikt verzije računa")
                .setMessage(msg)
                .setPositiveButton("V redu", (dialog, which) -> loadInitialOrderData())
                .setCancelable(false)
                .show();
    }

    private void handlePostError(Exception e) {
        enableEkran();
        String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "Neznana napaka";
        Toast.makeText(requireContext(), "Napaka pri oddaji naročila: " + msg, Toast.LENGTH_LONG).show();
    }

    private void odpriEditPozicijeDialog(NarociloItem item) {
        if (item == null || currentRacun == null || currentRacun.getRacPozic() == null) return;

        if (isRacunZaklenjen()) {
            Toast.makeText(requireContext(), "Račun je zaključen! Urejanje postavk ni dovoljeno.", Toast.LENGTH_SHORT).show();
            return;
        }

        PozicijaTp targetPoz = null;
        List<PozicijaTp> paketPozicije = null;
        boolean isPaket = (item.getPaketDistinct() > 0);

        if (isPaket) {
            paketPozicije = new ArrayList<>();
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null && !p.isRowDeleted() && p.getPaketDistinct() != null && p.getPaketDistinct() == item.getPaketDistinct()) {
                    paketPozicije.add(p);
                    if (targetPoz == null) {
                        targetPoz = p;
                    }
                }
            }
        } else {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null && !p.isRowDeleted()) {
                    if (item.getPozicijaId() != 0 && p.getPozicijaId() == item.getPozicijaId()) {
                        targetPoz = p;
                        break;
                    } else if (item.getNivo4Id() > 0 && p.getNivo4Id() != null && p.getNivo4Id() == item.getNivo4Id()) {
                        targetPoz = p;
                        break;
                    }
                }
            }
        }

        if (targetPoz == null) {
            Toast.makeText(requireContext(), "Postavka ni najdena v računu.", Toast.LENGTH_SHORT).show();
            return;
        }

        RacpozicEditDialog.show(requireContext(), targetPoz, isPaket, paketPozicije, new RacpozicEditDialog.OnItemEditedListener() {
            @Override
            public void onItemUpdated() {
                hasUnsavedChanges = true;
                populateOrderItemsFromCurrentRacun();
                updateOrderSummary();
            }

            @Override
            public void onItemDeleted() {
                hasUnsavedChanges = true;
                populateOrderItemsFromCurrentRacun();
                updateOrderSummary();
            }

            @Override
            public void onCancelled() {}
        });
    }

    private void handleGumbNarocilaLevoClick() {
        long now = SystemClock.elapsedRealtime();
        if (isOperationInProgress || (now - lastActionTime < DEBOUNCE_DELAY)) {
            return;
        }
        lastActionTime = now;

        Globals g = Globals.getInstance();
        if (g.isLokatorji()) {
            android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            if (currentRacun != null && currentRacun.getLokator() != null) {
                input.setText(currentRacun.getLokator().trim());
                input.setSelection(input.getText().length());
            }
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Lokator")
                    .setMessage("Vpiši št. lokatorja:")
                    .setView(input)
                    .setPositiveButton("V redu", (dialog, which) -> {
                        String val = input.getText().toString().trim();
                        if (currentRacun != null) {
                            currentRacun.setLokator(val);
                            hasUnsavedChanges = true;
                        }
                        updateGumbNarocilaLevo();
                    })
                    .setNegativeButton("Prekliči", null)
                    .show();
        } else if (g.isHodNarocila()) {
            android.widget.EditText input = new android.widget.EditText(requireContext());
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            if (g.getTekociHod() != null) {
                input.setText(g.getTekociHod().trim());
                input.setSelection(input.getText().length());
            }
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Hod-i")
                    .setMessage("Vpiši št. hoda:")
                    .setView(input)
                    .setPositiveButton("V redu", (dialog, which) -> {
                        String val = input.getText().toString().trim();
                        g.setTekociHod(val);
                        updateGumbNarocilaLevo();
                    })
                    .setNegativeButton("Prekliči", null)
                    .show();
        } else if (g.isDnCenik() && !g.isHodNarocila() && !g.isLokatorji()) {
            if (currentRacun == null) return;
            if (currentRacun.getDnId() != null && !currentRacun.getDnId().trim().isEmpty()
                    && currentRacun.getRacPozic() != null && !currentRacun.getRacPozic().isEmpty()) {
                Toast.makeText(requireContext(), "Zamenjava DN ni možna !", Toast.LENGTH_LONG).show();
                return;
            }
            DelovniNalogiDialog.show(requireContext(), currentRacun.getDnId(), new DelovniNalogiDialog.OnDelovniNalogSelectedListener() {
                @Override
                public void onSelected(DelovniNalogTp dn) {
                    if (currentRacun == null || dn == null) return;
                    if (currentRacun.getOriginalObject() == null) {
                        currentRacun.setOriginalObject(currentRacun.deepCopy());
                    }
                    currentRacun.setDnId(dn.getDnId());
                    if (dn.getPartnerId() != null && dn.getPartnerId() > 0) {
                        currentRacun.setPartnerId(dn.getPartnerId());
                    }
                    Globals.getInstance().vpisiKronologijo("IZBOR DN: " + dn.getDnId() + " R:" + currentRacun.getRacunId());
                    hasUnsavedChanges = true;
                    updateOrderSummary();
                    updateGumbNarocilaLevo();
                    // [DEBUG-TOAST] Toast.makeText(requireContext(), "Delovni nalog nastavljen: " + dn.getDnId(), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onRemoved() {
                    if (currentRacun != null) {
                        currentRacun.setDnId(null);
                        hasUnsavedChanges = true;
                        updateOrderSummary();
                        updateGumbNarocilaLevo();
                        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Delovni nalog odstranjen", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            if (preveriPopustNaRacunu()) {
                Toast.makeText(requireContext(), "Popust je že na računu!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentRacun == null || currentRacun.getRacPozic() == null || currentRacun.getRacPozic().isEmpty()) {
                Toast.makeText(requireContext(), "Na naročilu ni nobenega artikla!", Toast.LENGTH_SHORT).show();
                return;
            }
            Popust99Dialog.show(requireContext(), currentRacun.getZnesek(), new Popust99Dialog.OnPopustAppliedListener() {
                @Override
                public void onPopustApplied(BigDecimal procent, BigDecimal znesek) {
                    applyProcentPopust(procent, znesek);
                }

                @Override
                public void onCancelled() {}
            });
        }
    }

    private boolean preveriPopustNaRacunu() {
        if (currentRacun == null) return false;
        if (currentRacun.getLojalnostId() != null && currentRacun.getLojalnostId() > 0) return true;
        if (currentRacun.getAkcijaId() != null && currentRacun.getAkcijaId() > 0) return true;
        if (currentRacun.getRacPlaci() != null) {
            for (PlaciloTp pl : currentRacun.getRacPlaci()) {
                if (pl != null && !pl.isRowDeleted()) {
                    return true;
                }
            }
        }
        if (currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null && !p.isRowDeleted() && p.getZnesekPopust() != null && p.getZnesekPopust().compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyProcentPopust(BigDecimal procent, BigDecimal znesek) {
        if (currentRacun == null) return;
        if (currentRacun.getOriginalObject() == null) {
            currentRacun.setOriginalObject(currentRacun.deepCopy());
        }

        BigDecimal origZnesekPredPopustom = currentRacun.getZnesek();
        BigDecimal totalPopust = Globals.getInstance().popustNaRacun(currentRacun, procent, znesek);
        BigDecimal statusProcent = (procent != null && procent.compareTo(BigDecimal.ZERO) > 0)
                ? procent
                : ((origZnesekPredPopustom != null && origZnesekPredPopustom.compareTo(BigDecimal.ZERO) > 0)
                    ? totalPopust.multiply(BigDecimal.valueOf(100)).divide(origZnesekPredPopustom, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);

        int nextPozId = -1;
        if (currentRacun.getRacPlaci() != null) {
            for (PlaciloTp p : currentRacun.getRacPlaci()) {
                if (p != null && p.getPozicijaId() <= nextPozId) {
                    nextPozId = p.getPozicijaId() - 1;
                }
            }
        }

        final PlaciloTp pl = new PlaciloTp(currentRacun.getRacunId(), 99, totalPopust);
        pl.setPlaciloId(99);
        pl.setZnesek(totalPopust);
        pl.setDelniZnesek(BigDecimal.ZERO);
        pl.setStatus(statusProcent);
        pl.setPozicijaId(nextPozId);
        pl.setVerzijaZapisa(0);
        pl.setRowDeleted(false);
        pl.setOriginalObject(null);

        int tocId = (currentRacun.getTocilnicaId() != null && currentRacun.getTocilnicaId() > 0)
                ? currentRacun.getTocilnicaId()
                : (Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0);
        pl.setTocilnicaId(tocId);

        if (currentRacun.getRacPlaci() == null) {
            currentRacun.setRacPlaci(new ArrayList<>());
        }
        currentRacun.getRacPlaci().add(pl);
        currentRacun.preracunajVsote();

        populateOrderItemsFromCurrentRacun();
        updateOrderSummary();

        if (currentRacun.getRacunId() > 0) {
            disableEkran("Knjiženje popusta na strežnik...");
            executor.execute(() -> {
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    int mobileId = 1;
                    try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                    GetRacunRsTp response = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);
                    mainHandler.post(() -> {
                        enableEkran();
                        if (response != null && response.getRacGlava() != null) {
                            posodobiLokalniRacun(response.getRacGlava());
                            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Popust uspešno knjižen!", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        enableEkran();
                        Toast.makeText(requireContext(), "Napaka pri knjiženju popusta: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        } else {
            hasUnsavedChanges = true;
            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Popust dodan na naročilo!", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Hitro plačilo preko Hotkey gumba:
     * Parameter je PLACILO_ID (iz MobIni: HOTKEY3PLACILOID, HOTKEY4PLACILOID, HOTKEY5PLACILOID ali GOTOVINA=1).
     * 1. Iz PLACILO_ID poiščemo METODO preko Globals.placilometoda(placiloId).
     * 2. Preverimo ali je račun na strežniku (če ni, najprej kličemo setRacun).
     * 3. OBA parametra (placiloId za racplaciTp in metoda za logiko dialoga/intenta) se posredujeta v izvedbo.
     */
    public void izvediHitroPlacilo(final int placiloId) {
        long now = SystemClock.elapsedRealtime();
        if (isOperationInProgress || (now - lastActionTime < DEBOUNCE_DELAY)) {
            return;
        }
        lastActionTime = now;

        if (isRacunZaklenjen()) {
            Toast.makeText(requireContext(), "Račun je zaključen! Plačilo ni dovoljeno.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentRacun == null || currentRacun.getRacPozic() == null || currentRacun.getRacPozic().isEmpty()) {
            Toast.makeText(requireContext(), "Naročilo je prazno! Dodajte artikle.", Toast.LENGTH_SHORT).show();
            return;
        }

        currentRacun.preracunajVsote();
        BigDecimal trenPlacano = currentRacun.getPlacano() != null ? currentRacun.getPlacano() : BigDecimal.ZERO;
        BigDecimal trenZnesek = currentRacun.getZnesek() != null ? currentRacun.getZnesek() : BigDecimal.ZERO;
        final BigDecimal zaplacilo = trenZnesek.subtract(trenPlacano);

        if (zaplacilo.compareTo(BigDecimal.ZERO) <= 0) {
            Toast.makeText(requireContext(), "Račun je že v celoti plačan!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Poišči METODO iz PLACILO_ID
        final int metoda = Globals.getInstance().placilometoda(placiloId);

        NacPlacTp np = Globals.getInstance().getPlaciloById(placiloId);
        final String nacinNaziv = (np != null && np.getNaziv() != null && !np.getNaziv().trim().isEmpty())
                ? np.getNaziv()
                : (metoda == 1 ? "Gotovina" : ("Plačilo " + placiloId));

        final int racId = currentRacun.getRacunId();
        Globals.getInstance().vpisiKronologijo("KLIK HITRO PLACILO NAROCILA R:" + racId + " Placilo ID: " + placiloId + " Metoda: " + metoda);

        // 1. ZA VSA HITRA PLAČILA: najprej se preveri ali je račun na serverju, če ni kliče setRacun
        zagotoviRacunNaStrezniku(() -> {
            // 2. Izvede se plačilo in izpis do konca - z dodeljenim placiloId za racplaciTp in metodo za logiko
            if (Globals.getInstance().isHtPlaciloPotrdi()) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Potrditev hitrega plačila")
                    .setMessage(String.format(Locale.getDefault(), "Želite izvesti hitro plačilo (%s) v znesku %.2f €?", nacinNaziv, zaplacilo))
                    .setPositiveButton("Da", (d, w) -> izvediHitroPlaciloPoMetodiInterno(metoda, placiloId, nacinNaziv, zaplacilo))
                    .setNegativeButton("Ne", null)
                    .show();
            return;
        }
        izvediHitroPlaciloPoMetodiInterno(metoda, placiloId, nacinNaziv, zaplacilo);
        });
    }

    public void izvediHitroPlaciloPoMetodi(final int metoda) {
        NacPlacTp np = Globals.getInstance().getPlaciloByMetoda(metoda);
        int placiloId = (np != null) ? np.getPlaciloId() : ((metoda == 14) ? 2 : metoda);
        izvediHitroPlacilo(placiloId);
    }

    private void posodobiLokalniRacun(RacunTp saved) {
        if (saved == null) return;
        if ((saved.getRacPozic() == null || saved.getRacPozic().isEmpty()) && currentRacun != null && currentRacun.getRacPozic() != null) {
            saved.setRacPozic(currentRacun.getRacPozic());
        }
        if (saved.getRacPlaci() == null) {
            saved.setRacPlaci(new ArrayList<>());
        }
        if (currentRacun != null && currentRacun.getZnesek() != null && currentRacun.getZnesek().compareTo(BigDecimal.ZERO) > 0) {
            saved.setZnesek(currentRacun.getZnesek());
        }
        if (saved.getRacPozic() != null) {
            for (PozicijaTp p : saved.getRacPozic()) {
                if (p != null && (p.getNaziv() == null || p.getNaziv().trim().isEmpty()) && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                    String lookupName = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                    if (lookupName != null && !lookupName.trim().isEmpty()) {
                        p.setNaziv(lookupName.trim());
                    }
                }
            }
        }
        currentRacun = saved;
        currentRacun.preracunajVsote();
        currentRacun.setOriginalObject(currentRacun.deepCopy());
        if (currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null) p.setOriginalObject(p.deepCopy());
            }
        }
        if (currentRacun.getRacPlaci() != null) {
            for (PlaciloTp pl : currentRacun.getRacPlaci()) {
                if (pl != null) pl.setOriginalObject(pl.deepCopy());
            }
        }
        Globals.getInstance().setCurrentRacun(currentRacun);
        activeRacunId = currentRacun.getRacunId();
        prefs.setActiveRacunId(activeRacunId);
    }

    private void zagotoviRacunNaStrezniku(final Runnable onReady) {
        if (currentRacun == null) return;

        // Če račun še nima veljavnega strežniškega ID-ja ali ima neoddane postavke:
        if (currentRacun.getRacunId() <= 0 || hasUnpostedChanges()) {
            disableEkran("Shranjevanje naročila na strežnik pred plačilom...");
            executor.execute(() -> {
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    int mobileId = 1;
                    try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                    currentRacun.setStatus(1);
                    currentRacun.setMarker(activeMarker);
                    int fPosId = prefs.getfPosId() > 0 ? prefs.getfPosId() : (Globals.getInstance().getfPosId() != null && Globals.getInstance().getfPosId() > 0 ? Globals.getInstance().getfPosId() : 0);
                    currentRacun.setfPosId(fPosId);
                    int fPoslovniProstorId = Globals.getInstance().getfPoslovniProstorId() != null && Globals.getInstance().getfPoslovniProstorId() > 0 ? Globals.getInstance().getfPoslovniProstorId() : 0;
                    currentRacun.setfPoslovniProstorId(fPoslovniProstorId);
                    int tocId = Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0;
                    currentRacun.setTocilnicaId(tocId);
                    if (currentRacun.getKasiral() == null || currentRacun.getKasiral() <= 0) {
                        int tekocaOseba = Globals.getInstance().getTekocaOsebaId();
        currentRacun.setKasiral(tekocaOseba > 0 ? tekocaOseba : Globals.getInstance().getTekocaOsebaId());
                    }
                    if (currentRacun.getTipRacuna() == null || currentRacun.getTipRacuna() <= 0) {
                        currentRacun.setTipRacuna(1);
                    }
                    if (currentRacun.getStPogrinjkov() == null || currentRacun.getStPogrinjkov() <= 0) {
                        currentRacun.setStPogrinjkov(1);
                    }
                    if (currentRacun.getStKopij() == null) {
                        currentRacun.setStKopij(0);
                    }
                    currentRacun.preracunajVsote();

                    GetRacunRsTp resp = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);
                    mainHandler.post(() -> {
                        enableEkran();
                        if (resp != null && resp.getRacGlava() != null) {
                            posodobiLokalniRacun(resp.getRacGlava());
                            hasUnsavedChanges = false;
                            populateOrderItemsFromCurrentRacun();
                            if (onReady != null) onReady.run();
                        } else {
                            Toast.makeText(requireContext(), "Napaka pri shranjevanju računa pred plačilom!", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        enableEkran();
                        Toast.makeText(requireContext(), "Napaka pri sinhronizaciji računa: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            });
            return;
        }

        // Če račun že ima racunId > 0, preveri ali obstaja na strežniku preko getVerzijaOfRacglava
        disableEkran("Preverjanje računa na strežniku...");
        executor.execute(() -> {
            try {
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                int mobileId = 1;
                try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                int serverVer = RosKasaSoapClient.getVerzijaOfRacglava(serverUrl, token, currentRacun.getRacunId());
                if (serverVer <= 0) {
                    // Račun z racunId ne obstaja na strežniku -> takoj kliči setRacun
                    GetRacunRsTp resp = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);
                    mainHandler.post(() -> {
                        enableEkran();
                        if (resp != null && resp.getRacGlava() != null) {
                            posodobiLokalniRacun(resp.getRacGlava());
                            hasUnsavedChanges = false;
                            populateOrderItemsFromCurrentRacun();
                            if (onReady != null) onReady.run();
                        } else {
                            Toast.makeText(requireContext(), "Napaka pri shranjevanju računa pred plačilom!", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                if (serverVer > currentRacun.getVerzijaZapisa()) {
                    RacunTp refreshed = RosKasaSoapClient.getRacun(serverUrl, token, currentRacun.getRacunId());
                    mainHandler.post(() -> {
                        enableEkran();
                        if (refreshed != null) {
                            posodobiLokalniRacun(refreshed);
                            populateOrderItemsFromCurrentRacun();
                            updateOrderSummary();
                        }
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Račun spremenjen")
                                .setMessage("Račun je medtem spremenil drug natakar. Podatki so bili osveženi s strežnika — preverite znesek in ponovno izberite plačilo.")
                                .setPositiveButton("V redu", null)
                                .show();
                    });
                    return;
                }

                mainHandler.post(() -> {
                    enableEkran();
                    if (onReady != null) onReady.run();
                });
            } catch (Exception e) {
                Log.w(TAG, "Preverjanje računa na strežniku opozorilo: " + e.getMessage());
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    int mobileId = 1;
                    try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                    GetRacunRsTp resp = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);
                    mainHandler.post(() -> {
                        enableEkran();
                        if (resp != null && resp.getRacGlava() != null) {
                            posodobiLokalniRacun(resp.getRacGlava());
                            hasUnsavedChanges = false;
                            populateOrderItemsFromCurrentRacun();
                        }
                        if (onReady != null) onReady.run();
                    });
                } catch (Exception ex2) {
                    mainHandler.post(() -> {
                        enableEkran();
                        Toast.makeText(requireContext(), "Napaka pri sinhronizaciji računa: " + ex2.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void izvediHitroPlaciloPoMetodiInterno(final int metoda, final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo) {
        final boolean isIntentOn = Globals.getInstance().isPosIntentActive();

        if (metoda == 14 && isIntentOn) {
            // Intent plačilo (če je vklopljeno)
            izvediPosPlacilo(placiloId, nacinNaziv, zaplacilo);
        } else if (metoda == 6 || placiloId == 8) {
            // Metoda 6: hotel kredit
            HotelSobeDialog.show(requireContext(), zaplacilo, selectedRoom -> {
                zakljuciInNatisniHotelKredit(placiloId, nacinNaziv, zaplacilo, selectedRoom);
            });
        } else if (metoda == 3) {
            // Delphi RosKasaLight FormKasaMobile.pas KlikniHtPlacilo (case tempmetoda of 3):
            // Če je KKARTICA_KUPECID_ROCNO <> 0 (ali ima plačilo fiksni KUPEC_ID), takoj poknjiži, zaključi in izpiše račun!
            Globals g = Globals.getInstance();
            NacPlacTp np = g.getPlaciloById(placiloId);
            int PlaciloPartnerStoritevId = (np != null && np.getStoritevId() != null) ? np.getStoritevId() : 0;
            int KKARTICA_KUPECID_ROCNO = g.getkKarticaKupecIdRocno();
            int fiksniKupecId = KKARTICA_KUPECID_ROCNO;
            if (fiksniKupecId <= 0 && np != null && np.getKupecId() != null && np.getKupecId() > 0) {
                fiksniKupecId = np.getKupecId().intValue();
            }

            if (fiksniKupecId > 0) {
                g.vpisiKronologijo("Hitro plačilo KK ročno avtomatski KUPEC_ID: " + fiksniKupecId + " za R:" + (currentRacun != null ? currentRacun.getRacunId() : 0));
                zakljuciInNatisniRocnaKreditnaKartica(placiloId, nacinNaziv, zaplacilo, fiksniKupecId);
            } else {
                // Izbor partnerja iz liste za tip partner = STORITEV_ID
                PartnerVnosDialog.show(requireContext(), zaplacilo, PlaciloPartnerStoritevId, (partnerId, naziv, naslov, davcna, stNarocilnice, rabat) -> {
                    zakljuciInNatisniPartner(placiloId, nacinNaziv, zaplacilo, partnerId, naziv, naslov, davcna, stNarocilnice, rabat);
                });
            }
        } else if (metoda == 4 || placiloId == 4) {
            // Metoda 4: partner / dobavnica
            NacPlacTp np = Globals.getInstance().getPlaciloById(placiloId);
            int PlaciloPartnerStoritevId = 0;
            if (np != null && np.getStoritevId() != null) {
                PlaciloPartnerStoritevId = np.getStoritevId();
            }
            PartnerVnosDialog.show(requireContext(), zaplacilo, PlaciloPartnerStoritevId, (partnerId, naziv, naslov, davcna, stNarocilnice, rabat) -> {
                zakljuciInNatisniPartner(placiloId, nacinNaziv, zaplacilo, partnerId, naziv, naslov, davcna, stNarocilnice, rabat);
            });
        } else {
            // Metoda 1: gotovina in ostale ne-kartične metode
            zakljuciInNatisniHitroPlacilo(placiloId, nacinNaziv, zaplacilo);
        }
    }

    private void izvediPosPlacilo(final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo) {
        if (zaplacilo == null || zaplacilo.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (!(getActivity() instanceof MainActivity)) {
            Toast.makeText(requireContext(), "Napaka: MainActivity ni dostopen!", Toast.LENGTH_SHORT).show();
            return;
        }
        MainActivity activity = (MainActivity) getActivity();
        if (currentRacun == null) return;
        final int racunId = currentRacun.getRacunId();

        if (Globals.getInstance().isPayTenA()) {
            PaytenPaymentService.prepareAndCheckPurchase(requireContext(), racunId, placiloId, zaplacilo, new PaytenPaymentService.PaytenLaunchCallback() {
                @Override
                public void onProceedLaunch() {
                    Globals.getInstance().setPayTenAPlaciloId(placiloId);
                    Globals.getInstance().vpisiKronologijo("PAYTEN purchase za R:" + racunId + " Z:" + zaplacilo);
                    android.content.Intent paytenIntent = PaytenPaymentService.buildPaymentIntent(requireContext(), racunId, 0, zaplacilo, "purchase", null);
                    disableEkran("Čakam na plačilo PayTen...");
                    activity.launchPayten(paytenIntent, new MainActivity.PaymentResultListener() {
                        @Override
                        public void onPaytenResult(int resultCode, android.content.Intent data) {
                            enableEkran();
                            handlePaytenResult(resultCode, data, placiloId, nacinNaziv, zaplacilo);
                        }

                        @Override
                        public void onSixTapResult(int resultCode, android.content.Intent data) {}
                    });
                }

                @Override
                public void onRecoverConfirmed(PaytenResponse confirmedResp) {
                    Toast.makeText(requireContext(), "Za ta račun je plačilo na POS terminalu že odobreno! Zaključujem sinhronizacijo računa...", Toast.LENGTH_LONG).show();
                    int recPlaciloId = confirmedResp.getpPlaciloId() > 0 ? confirmedResp.getpPlaciloId() : placiloId;
                    BigDecimal recZnesek = confirmedResp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0
                            ? confirmedResp.getZnesekPOS()
                            : (confirmedResp.getpZnesek().compareTo(BigDecimal.ZERO) > 0 ? confirmedResp.getpZnesek() : zaplacilo);
                    zakljuciUspesnoPosPlacilo(nacinNaziv, recPlaciloId, recZnesek, confirmedResp.getCardNumber(),
                            confirmedResp.getPaymentSolutionReference(), null, confirmedResp.getReceipt(),
                            confirmedResp.getClient(), confirmedResp.getApplicationIdentifier(),
                            confirmedResp.getpAuthorizationCode(), confirmedResp.getpAuthorizationCode(),
                            confirmedResp.getpNapitnina(), "purchase");
                }

                @Override
                public void onBlocked(String message) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("PayTen")
                            .setMessage(message)
                            .setPositiveButton("V redu", null)
                            .show();
                }
            });
        } else if (Globals.getInstance().isSixTap()) {
            SixTapPaymentService.prepareAndCheckPayment(requireContext(), racunId, new SixTapPaymentService.SixTapLaunchCallback() {
                @Override
                public void onProceedLaunch() {
                    Globals.getInstance().setSixTapPlaciloId(placiloId);
                    android.content.Intent sixTapIntent = SixTapPaymentService.buildPaymentIntent(requireContext(), racunId, 0, zaplacilo, BigDecimal.ZERO, SixTapPaymentService.OP_PAYMENT, null, null);
                    disableEkran("Čakam na plačilo Worldline Tap On...");
                    activity.launchSixTap(sixTapIntent, new MainActivity.PaymentResultListener() {
                        @Override
                        public void onPaytenResult(int resultCode, android.content.Intent data) {}

                        @Override
                        public void onSixTapResult(int resultCode, android.content.Intent data) {
                            enableEkran();
                            handleSixTapResult(resultCode, data, placiloId, nacinNaziv, zaplacilo);
                        }
                    });
                }

                @Override
                public void onRecoverConfirmed(SixTapResponse confirmedResp) {
                    Toast.makeText(requireContext(), "Zaključujem že potrjeno Worldline Tap On plačilo...", Toast.LENGTH_LONG).show();
                    int recPlaciloId = confirmedResp.getpPlaciloId() > 0 ? confirmedResp.getpPlaciloId() : placiloId;
                    BigDecimal recZnesek = confirmedResp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0
                            ? confirmedResp.getZnesekPOS()
                            : (confirmedResp.getpZnesek().compareTo(BigDecimal.ZERO) > 0 ? confirmedResp.getpZnesek() : zaplacilo);
                    zakljuciUspesnoPosPlacilo(nacinNaziv, recPlaciloId, recZnesek, confirmedResp.getCardnumber(),
                            confirmedResp.getPaymentSolutionReference(), confirmedResp.getpWpiSessionId(),
                            confirmedResp.getClient(), confirmedResp.getMerchant(),
                            confirmedResp.getApplicationIdentifier(), confirmedResp.getAcqreference(),
                            confirmedResp.getAuthNumber(), confirmedResp.getpNapitnina(), confirmedResp.getpOperacija());
                }

                @Override
                public void onAutoCheckLastTransaction(String sessionId, int pendingRacunId) {
                    Toast.makeText(requireContext(), "Samodejno preverjam status prejšnje transakcije (#" + pendingRacunId + ")...", Toast.LENGTH_LONG).show();
                    izvediSixTapLastTransaction(sessionId, pendingRacunId > 0 ? pendingRacunId : racunId, zaplacilo, placiloId, nacinNaziv);
                }

                @Override
                public void onBlocked(String message) {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Nedokončano SixTap plačilo")
                            .setMessage(message)
                            .setPositiveButton("V redu", null)
                            .show();
                }
            });
        } else {
            // Intent plačilo NI vklopljeno! Izvedi direktno kartično plačilo in izpis do konca!
            zakljuciInNatisniHitroPlacilo(placiloId, nacinNaziv, zaplacilo);
        }
    }

    private void handlePaytenResult(int resultCode, android.content.Intent data, int placiloId, String nacinNaziv, BigDecimal zaplacilo) {
        Globals.getInstance().setPayTenIntransaction(false);
        final int racunId = currentRacun != null ? currentRacun.getRacunId() : 0;
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            PaytenResponse resp = PaytenPaymentService.parseResponseIntent(data);
            if (resp.isSuccess()) {
                resp.setpRacunId(racunId);
                resp.setpPlaciloId(placiloId);
                resp.setpZnesek(zaplacilo);
                resp.setpOperacija("purchase");
                PaymentRecoveryManager.saveConfirmedPaytenTransaction(requireContext(), resp, PaymentRecoveryManager.PAYTEN_STATE_POS_CONFIRMED);
                Globals.getInstance().vpisiKronologijo("PAYTEN SHRAMBA STANJA: R:" + racunId + " NovoStanje:CONFIRMED");
                Globals.getInstance().vpisiKronologijo("PAYTEN RESULT OK R:" + racunId + " M_REF:" + resp.getPaymentSolutionReference() + " Auth:" + resp.getpAuthorizationCode());
                BigDecimal znesekPOS = resp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0 ? resp.getZnesekPOS() : zaplacilo;
                zakljuciUspesnoPosPlacilo(nacinNaziv, placiloId, znesekPOS, resp.getCardNumber(), resp.getPaymentSolutionReference(),
                        null, resp.getReceipt(), resp.getClient(), resp.getApplicationIdentifier(), resp.getpAuthorizationCode(),
                        resp.getpAuthorizationCode(), resp.getpNapitnina(), "purchase");
            } else {
                PaymentRecoveryManager.clearPaytenPendingPayment(requireContext());
                Globals.getInstance().vpisiKronologijo("PAYTEN RESULT FAIL R:" + racunId + " Code:" + resp.getCode() + " Msg:" + resp.getMessage());
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Napaka PayTen")
                        .setMessage("Transakcija ni uspela: " + resp.getCode() + " " + resp.getMessage())
                        .setPositiveButton("V redu", null)
                        .show();
            }
        } else {
            PaymentRecoveryManager.clearPaytenPendingPayment(requireContext());
            Globals.getInstance().vpisiKronologijo("PAYTEN preklican ali neuspešen R:" + racunId);
            Toast.makeText(requireContext(), "Plačilo PayTen preklicano ali neuspešno.", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSixTapResult(int resultCode, android.content.Intent data, int placiloId, String nacinNaziv, BigDecimal zaplacilo) {
        Globals.getInstance().setSixtapintransaction(false);
        final int racunId = currentRacun != null ? currentRacun.getRacunId() : 0;
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            SixTapResponse resp = SixTapPaymentService.parseResponseIntent(data);
            if (resp.isSuccess()) {
                resp.setpRacunId(racunId);
                resp.setpPlaciloId(placiloId);
                resp.setpZnesek(zaplacilo);
                PaymentRecoveryManager.saveConfirmedPOSTransaction(requireContext(), resp, PaymentRecoveryManager.POS_STATE_POS_CONFIRMED);
                BigDecimal znesekPOS = resp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0 ? resp.getZnesekPOS() : zaplacilo;
                zakljuciUspesnoPosPlacilo(nacinNaziv, placiloId, znesekPOS, resp.getCardnumber(), resp.getPaymentSolutionReference(),
                        resp.getpWpiSessionId(), resp.getClient(), resp.getMerchant(), resp.getApplicationIdentifier(), resp.getAcqreference(),
                        resp.getAuthNumber(), resp.getpNapitnina(), resp.getpOperacija());
            } else {
                PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                Globals.getInstance().vpisiKronologijo("SIXTAP RESULT FAIL R:" + racunId + " Error: " + resp.getErrorCondition() + " " + resp.getRemark());
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Napaka SixTap")
                        .setMessage("Transakcija ni uspela: " + resp.getErrorCondition() + " " + resp.getRemark())
                        .setPositiveButton("V redu", null)
                        .show();
            }
        } else {
            // Preklic s strani uporabnika ali napaka klica: shrani recovery in po potrebi sproži LAST_TRANSACTION (ne briši!)
            Globals g = Globals.getInstance();
            final String sessionId = g.getZadnjiWpiSessionId() != null ? g.getZadnjiWpiSessionId() : "";
            int amountCents = zaplacilo.multiply(new BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP).intValue();

            g.vpisiKronologijo("SIXTAP RESULT CANCEL/FAIL R:" + racunId + " SESSION_ID: " + sessionId);
            if (g.isSixTapManualLast() || g.isSixTapAutoLast()) {
                PaymentRecoveryManager.saveRecoveryPayment(requireContext(), sessionId, String.valueOf(racunId), String.valueOf(amountCents),
                        sessionId, String.valueOf(racunId), String.valueOf(amountCents));
                if (g.isSixTapAutoLast()) {
                    Toast.makeText(requireContext(), "Prekinjeno. Avtomatsko preverjam status plačila...", Toast.LENGTH_SHORT).show();
                    izvediSixTapLastTransaction(sessionId, racunId, zaplacilo, placiloId, nacinNaziv);
                } else {
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Plačilo prekinjeno")
                            .setMessage("Plačila - Tap On tipka !")
                            .setPositiveButton("V redu", null)
                            .show();
                }
            } else {
                PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                Toast.makeText(requireContext(), "Plačilo Worldline Tap On preklicano.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void izvediSixTapLastTransaction(String sessionId, int racunId, BigDecimal preostanek, int placiloId, String nacinNaziv) {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        android.content.Intent lastIntent = SixTapPaymentService.buildPaymentIntent(requireContext(), racunId, 0, preostanek, BigDecimal.ZERO,
                SixTapPaymentService.OP_LAST_TRANSACTION, null, sessionId);
        disableEkran("Preverjam zadnjo transakcijo na POS terminalu...");
        activity.launchSixTap(lastIntent, new MainActivity.PaymentResultListener() {
            @Override
            public void onPaytenResult(int resultCode, android.content.Intent data) {}

            @Override
            public void onSixTapResult(int resultCode, android.content.Intent data) {
                enableEkran();
                Globals.getInstance().setSixtapintransaction(false);
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    SixTapResponse resp = SixTapPaymentService.parseResponseIntent(data);
                    if (resp.isSuccess()) {
                        resp.setpRacunId(racunId);
                        resp.setpPlaciloId(placiloId);
                        resp.setpZnesek(preostanek);
                        PaymentRecoveryManager.saveConfirmedPOSTransaction(requireContext(), resp, PaymentRecoveryManager.POS_STATE_POS_CONFIRMED);
                        BigDecimal znesekPOS = resp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0 ? resp.getZnesekPOS() : preostanek;
                        zakljuciUspesnoPosPlacilo(nacinNaziv, placiloId, znesekPOS, resp.getCardnumber(), resp.getPaymentSolutionReference(),
                                resp.getpWpiSessionId(), resp.getClient(), resp.getMerchant(), resp.getApplicationIdentifier(),
                                resp.getAcqreference(), resp.getAuthNumber(), resp.getpNapitnina(), resp.getpOperacija());
                    } else {
                        PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                        Toast.makeText(requireContext(), "Zadnja transakcija ni bila potrjena: " + resp.getErrorCondition() + " " + resp.getRemark(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(requireContext(), "Preverjanje zadnje transakcije prekinjeno.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void zakljuciUspesnoPosPlacilo(final String nacin, final int placiloId, final BigDecimal znesekPOS,
                                           final String stKartice, final String mRef, final String wpiSessionId,
                                           final String slipClient, final String slipMerchant,
                                           final String appIdentifier, final String authRef, final String authNumber,
                                           final BigDecimal napitnina, final String operacija) {
        if (currentRacun == null) return;
        final int racunId = currentRacun.getRacunId();

        // 1. ZNESEK RAČUNA: Ob uspešni POS transakciji mora biti v DELNI_ZNESEK zapisan POS ZNESEK - NAPITNINA
        BigDecimal znesekRacuna = (napitnina != null && napitnina.compareTo(BigDecimal.ZERO) > 0)
                ? znesekPOS.subtract(napitnina)
                : znesekPOS;
        if (znesekRacuna.compareTo(BigDecimal.ZERO) <= 0) {
            znesekRacuna = znesekPOS;
        }

        final PlaciloTp pl = new PlaciloTp(racunId, placiloId, znesekRacuna);
        pl.setPlaciloId(placiloId);
        pl.setDelniZnesek(znesekRacuna);
        pl.setZnesek(BigDecimal.ZERO);

        int kupecId = Globals.getInstance().resolvePosKupecId(placiloId, stKartice, slipClient, slipMerchant, appIdentifier);
        if (kupecId > 0) {
            pl.setKupecId(kupecId);
            pl.setPartnerId(kupecId);
        }
        pl.setMRef(mRef != null ? mRef.trim() : "");
        pl.setWpiSessionId(wpiSessionId != null ? wpiSessionId.trim() : "");
        if (napitnina != null && napitnina.compareTo(BigDecimal.ZERO) > 0) {
            pl.setNapitnina(napitnina);
        } else {
            pl.setNapitnina(null);
        }

        // 2. SLIP_EMA: Kreiraj SlipEmaTp za strežnik
        String fullSlip = (slipClient != null && !slipClient.isEmpty()) ? slipClient : (slipMerchant != null ? slipMerchant : "");
        if (!fullSlip.isEmpty()) {
            Globals.getInstance().setZadnjiSlip(racunId, fullSlip);
        }

        String op = (operacija != null && !operacija.isEmpty()) ? operacija : SixTapPaymentService.OP_PAYMENT;
        SlipEmaTp slip = new SlipEmaTp();
        slip.setStevilkaRacuna(racunId);
        slip.setPozicijaId(1);
        slip.setSlipPrint(slipClient != null ? slipClient : "");
        slip.setSlipPrints(slipMerchant != null ? slipMerchant : "");

        String samoStKartice = SixTapPaymentService.extractDigitsOnly(stKartice);
        slip.setStevilkaKartice(samoStKartice);

        slip.setUspelo("DA");
        slip.setProjektId(3);
        slip.setStType(op);
        slip.setZnesek(znesekRacuna);
        slip.setZnesekSlip(znesekPOS);
        slip.setAcqTransRef(wpiSessionId != null && !wpiSessionId.isEmpty() ? wpiSessionId : mRef);
        slip.setAppIdentifier(appIdentifier != null ? appIdentifier : "");
        slip.setAcqReference(authRef != null ? authRef : "");
        slip.setAuthReference(authNumber != null && !authNumber.isEmpty() ? authNumber : (authRef != null ? authRef : ""));
        slip.setAuthNumber(authNumber != null ? authNumber : "");

        String cleanFullCard = SixTapPaymentService.cleanCardNumber(stKartice);
        slip.setCardNumber(cleanFullCard);

        slip.setAvtorizacija("PAY");
        int tocId = (currentRacun.getTocilnicaId() != null && currentRacun.getTocilnicaId() > 0)
                ? currentRacun.getTocilnicaId()
                : (Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0);
        slip.setStrmId(tocId);

        zakljuciInNatisniHitroPlaciloInterno(pl, nacin, znesekRacuna, slip);
    }

    private void zakljuciInNatisniHotelKredit(final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo, final KartprijTp hotelSoba) {
        if (currentRacun == null) return;
        final PlaciloTp pl = new PlaciloTp(currentRacun.getRacunId(), placiloId, zaplacilo);
        pl.setPlaciloId(placiloId);
        pl.setDelniZnesek(zaplacilo);
        pl.setZnesek(BigDecimal.ZERO);
        if (hotelSoba != null) {
            if (hotelSoba.getHisCenikAi() > 0) {
                pl.setHisCenikAi(hotelSoba.getHisCenikAi());
                pl.setGostPrijavaId(0);
                pl.setStKartice("SK-" + hotelSoba.getHisCenikAi());
                pl.setNazivPartner(!hotelSoba.getHisCenikNaziv().isEmpty() ? hotelSoba.getHisCenikNaziv() : hotelSoba.getImeGosta());
            } else {
                pl.setStKartice(String.valueOf(hotelSoba.getProstorId()));
                pl.setGostPrijavaId(hotelSoba.getPrijavaId());
                pl.setNazivPartner(hotelSoba.getImeGosta());
            }
        }
        zakljuciInNatisniHitroPlaciloInterno(pl, nacinNaziv, zaplacilo);
    }

    private void zakljuciInNatisniPartner(final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo,
                                          final int partnerId, final String naziv, final String naslov, final String davcna,
                                          final String stNarocilnice, final BigDecimal rabat) {
        if (currentRacun == null) return;
        BigDecimal dejanskiZnesek = zaplacilo;
        if (rabat != null && rabat.compareTo(BigDecimal.ZERO) > 0) {
            boolean zeImaPopust = false;
            if (currentRacun.getRacPlaci() != null) {
                for (PlaciloTp pl : currentRacun.getRacPlaci()) {
                    if (pl != null && !pl.isRowDeleted() && pl.getPlaciloId() == 99) {
                        zeImaPopust = true;
                        break;
                    }
                }
            }
            if (!zeImaPopust) {
                BigDecimal totalPopust = Globals.getInstance().popustNaRacun(currentRacun, rabat, BigDecimal.ZERO);
                if (totalPopust != null && totalPopust.compareTo(BigDecimal.ZERO) > 0) {
                    int nextPopPozId = -1;
                    if (currentRacun.getRacPlaci() != null) {
                        for (PlaciloTp p : currentRacun.getRacPlaci()) {
                            if (p != null && p.getPozicijaId() <= nextPopPozId) nextPopPozId = p.getPozicijaId() - 1;
                        }
                    } else {
                        currentRacun.setRacPlaci(new ArrayList<>());
                    }
                    PlaciloTp pl99 = new PlaciloTp(currentRacun.getRacunId(), 99, totalPopust);
                    pl99.setPlaciloId(99);
                    pl99.setZnesek(totalPopust);
                    pl99.setDelniZnesek(BigDecimal.ZERO);
                    pl99.setStatus(rabat);
                    pl99.setPozicijaId(nextPopPozId);
                    pl99.setVerzijaZapisa(0);
                    pl99.setRowDeleted(false);
                    pl99.setOriginalObject(null);
                    int tocId = (currentRacun.getTocilnicaId() != null && currentRacun.getTocilnicaId() > 0)
                            ? currentRacun.getTocilnicaId()
                            : (Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0);
                    pl99.setTocilnicaId(tocId);
                    currentRacun.getRacPlaci().add(pl99);
                }
                currentRacun.preracunajVsote();
                BigDecimal trenPlacano = currentRacun.getPlacano() != null ? currentRacun.getPlacano() : BigDecimal.ZERO;
                BigDecimal trenZnesek = currentRacun.getZnesek() != null ? currentRacun.getZnesek() : BigDecimal.ZERO;
                dejanskiZnesek = trenZnesek.subtract(trenPlacano);
            }
        }

        final PlaciloTp pl = new PlaciloTp(currentRacun.getRacunId(), placiloId, dejanskiZnesek);
        pl.setPlaciloId(placiloId);
        pl.setDelniZnesek(dejanskiZnesek);
        pl.setZnesek(BigDecimal.ZERO);
        if (partnerId > 0) {
            pl.setPartnerId(partnerId);
            pl.setKupecId(partnerId);
            currentRacun.setPartnerId(partnerId);
        }
        if (naziv != null && !naziv.isEmpty()) pl.setNazivPartner(naziv);
        if (naslov != null && !naslov.isEmpty()) pl.setNaslovPartner(naslov);
        if (davcna != null && !davcna.isEmpty()) pl.setDavcnaSt(davcna);
        if (stNarocilnice != null && !stNarocilnice.isEmpty()) pl.setStNarocilnice(stNarocilnice);

        zakljuciInNatisniHitroPlaciloInterno(pl, nacinNaziv, dejanskiZnesek);
    }

    private void zakljuciInNatisniRocnaKreditnaKartica(final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo, final int kupecId) {
        if (currentRacun == null) return;
        final PlaciloTp pl = new PlaciloTp(currentRacun.getRacunId(), placiloId, zaplacilo);
        pl.setPlaciloId(placiloId);
        pl.setDelniZnesek(zaplacilo);
        pl.setZnesek(BigDecimal.ZERO);
        if (kupecId > 0) {
            pl.setKupecId(kupecId);
            pl.setPartnerId(kupecId);
            currentRacun.setPartnerId(kupecId);
        }
        zakljuciInNatisniHitroPlaciloInterno(pl, nacinNaziv, zaplacilo);
    }

    private void zakljuciInNatisniHitroPlacilo(final int placiloId, final String nacinNaziv, final BigDecimal zaplacilo) {
        if (currentRacun == null) return;
        final PlaciloTp pl = new PlaciloTp(currentRacun.getRacunId(), placiloId, zaplacilo);
        pl.setPlaciloId(placiloId);
        pl.setDelniZnesek(zaplacilo);
        pl.setZnesek(BigDecimal.ZERO);
        Globals g = Globals.getInstance();
        int metoda = g.placilometoda(placiloId);
        if (metoda == 3) {
            NacPlacTp np = g.getPlaciloById(placiloId);
            int fiksniKupecId = g.getkKarticaKupecIdRocno();
            if (fiksniKupecId <= 0 && np != null && np.getKupecId() != null && np.getKupecId() > 0) {
                fiksniKupecId = np.getKupecId().intValue();
            }
            if (fiksniKupecId > 0) {
                pl.setKupecId(fiksniKupecId);
                pl.setPartnerId(fiksniKupecId);
                currentRacun.setPartnerId(fiksniKupecId);
            }
        }
        zakljuciInNatisniHitroPlaciloInterno(pl, nacinNaziv, zaplacilo);
    }

    private boolean isPlaciloFiskalno(int plId) {
        NacPlacTp np = Globals.getInstance().getPlaciloById(plId);
        if (np != null && np.getFiskalno() != null) {
            return np.getFiskalno() == 1;
        }
        return plId == 1 || plId == 2 || plId == 3 || plId == 399;
    }

    private void zakljuciInNatisniHitroPlaciloInterno(final PlaciloTp pl, final String nacinNaziv, final BigDecimal zaplacilo) {
        zakljuciInNatisniHitroPlaciloInterno(pl, nacinNaziv, zaplacilo, null);
    }

    private void zakljuciInNatisniHitroPlaciloInterno(final PlaciloTp pl, final String nacinNaziv, final BigDecimal zaplacilo, final SlipEmaTp slip) {
        disableEkran("Knjiženje in zaključek (" + nacinNaziv + ")...");

        int nextPozId = -1;
        if (currentRacun.getRacPlaci() != null) {
            for (PlaciloTp p : currentRacun.getRacPlaci()) {
                if (p != null && p.getPozicijaId() <= nextPozId) {
                    nextPozId = p.getPozicijaId() - 1;
                }
            }
        }

        pl.setPozicijaId(nextPozId);
        pl.setVerzijaZapisa(0);
        pl.setRowDeleted(false);
        pl.setOriginalObject(null);

        int tocId = (currentRacun.getTocilnicaId() != null && currentRacun.getTocilnicaId() > 0)
                ? currentRacun.getTocilnicaId()
                : (Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0 ? Globals.getInstance().getTocilnicaId() : 0);
        pl.setTocilnicaId(tocId);

        if (currentRacun.getRacPozic() != null) {
            for (PozicijaTp p : currentRacun.getRacPozic()) {
                if (p != null) p.setNeNarocaj(true);
            }
        }
        if (currentRacun.getRacPlaci() == null) {
            currentRacun.setRacPlaci(new ArrayList<>());
        }
        boolean zeObstajaLokalno = false;
        String trimmedMRef = pl.getMRef() != null ? pl.getMRef().trim() : "";
        if (!trimmedMRef.isEmpty()) {
            for (PlaciloTp obst : currentRacun.getRacPlaci()) {
                if (obst != null && !obst.isRowDeleted() && trimmedMRef.equals(obst.getMRef())) {
                    zeObstajaLokalno = true;
                    break;
                }
            }
        }
        if (!zeObstajaLokalno) {
            currentRacun.getRacPlaci().add(pl);
        }
        if (slip != null && Globals.getInstance().isPayTenA() && "purchase".equalsIgnoreCase(slip.getStType())) {
            PaymentRecoveryManager.setPaytenState(requireContext(), PaymentRecoveryManager.PAYTEN_STATE_LOCAL_RECORDED);
            Globals.getInstance().vpisiKronologijo("PAYTEN SHRAMBA STANJA: R:" + currentRacun.getRacunId() + " NovoStanje:LOCAL_RECORDED");
        }

        // Fiskalizacija:
        boolean imaFiskalnoPlacilo = false;
        for (PlaciloTp p : currentRacun.getRacPlaci()) {
            if (p != null && !p.isRowDeleted() && p.getPlaciloId() != 99) {
                if (isPlaciloFiskalno(p.getPlaciloId())) {
                    imaFiskalnoPlacilo = true;
                    break;
                }
            }
        }
        currentRacun.setFiskalizacija(imaFiskalnoPlacilo ? 1 : null);
        currentRacun.setStatus(2); // ZAKLJUČEN račun
        currentRacun.setMarker(activeMarker);
        int fPosId = prefs.getfPosId() > 0 ? prefs.getfPosId() : (Globals.getInstance().getfPosId() != null && Globals.getInstance().getfPosId() > 0 ? Globals.getInstance().getfPosId() : 0);
        currentRacun.setfPosId(fPosId);
        int fPoslovniProstorId = Globals.getInstance().getfPoslovniProstorId() != null && Globals.getInstance().getfPoslovniProstorId() > 0 ? Globals.getInstance().getfPoslovniProstorId() : 0;
        currentRacun.setfPoslovniProstorId(fPoslovniProstorId);
        currentRacun.setTocilnicaId(tocId);
        if (Globals.getInstance().getTekocaOsebaId() > 0) {
            currentRacun.setKasiral(Globals.getInstance().getTekocaOsebaId());
        } else if (currentRacun.getKasiral() == null || currentRacun.getKasiral() <= 0) {
            int tekocaOseba = Globals.getInstance().getTekocaOsebaId();
        currentRacun.setKasiral(tekocaOseba > 0 ? tekocaOseba : Globals.getInstance().getTekocaOsebaId());
        }
        if (currentRacun.getTipRacuna() == null || currentRacun.getTipRacuna() <= 0) {
            currentRacun.setTipRacuna(1);
        }
        if (currentRacun.getStPogrinjkov() == null || currentRacun.getStPogrinjkov() <= 0) {
            currentRacun.setStPogrinjkov(1);
        }
        if (currentRacun.getStKopij() == null) {
            currentRacun.setStKopij(0);
        }

        BigDecimal trenPlacano = currentRacun.getPlacano() != null ? currentRacun.getPlacano() : BigDecimal.ZERO;
        currentRacun.setPlacano(trenPlacano.add(zaplacilo));
        currentRacun.preracunajVsote();

        executor.execute(() -> {
            try {
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                int mobileId = 1;
                try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                // 1. setSlipEma (če je POS plačilo)
                if (slip != null) {
                    Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma R:" + currentRacun.getRacunId()
                            + " Kartica:" + slip.getStevilkaKartice() + " Auth:" + slip.getAuthNumber()
                            + " M_REF: " + slip.getAcqTransRef() + " Op: " + slip.getStType());
                    boolean slipOk = RosKasaSoapClient.setSlipEma(serverUrl, token, slip);
                    if (slipOk) {
                        Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma USPEH R:" + currentRacun.getRacunId());
                    } else {
                        Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma OPOZORILO: strežnik ni potrdil slipa R:" + currentRacun.getRacunId());
                    }
                }

                // 2. setRacun
                GetRacunRsTp response = RosKasaSoapClient.setRacun(serverUrl, token, mobileId, currentRacun);
                if (slip != null && Globals.getInstance().isPayTenA() && "purchase".equalsIgnoreCase(slip.getStType())) {
                    Globals.getInstance().setPayTenIntransaction(false);
                    PaymentRecoveryManager.setPaytenState(requireContext(), PaymentRecoveryManager.PAYTEN_STATE_COMPLETED);
                    Globals.getInstance().vpisiKronologijo("PAYTEN SHRAMBA STANJA: R:" + currentRacun.getRacunId() + " NovoStanje:COMPLETED");
                } else {
                    PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                }

                RacunTp racunZaTisk = currentRacun;
                if (response != null && response.getRacGlava() != null) {
                    RacunTp returned = response.getRacGlava();
                    if ((returned.getRacPozic() == null || returned.getRacPozic().isEmpty()) && currentRacun.getRacPozic() != null) {
                        returned.setRacPozic(currentRacun.getRacPozic());
                    }
                    if (returned.getRacPlaci() == null && currentRacun.getRacPlaci() != null) {
                        returned.setRacPlaci(currentRacun.getRacPlaci());
                    }
                    if (returned.getFiskalizacija() == null && currentRacun.getFiskalizacija() != null) {
                        returned.setFiskalizacija(currentRacun.getFiskalizacija());
                    }
                    if (currentRacun.getZnesek() != null && currentRacun.getZnesek().compareTo(BigDecimal.ZERO) > 0) {
                        returned.setZnesek(currentRacun.getZnesek());
                    }
                    racunZaTisk = returned;
                }

                if (racunZaTisk != null && racunZaTisk.getRacPozic() != null) {
                    for (PozicijaTp p : racunZaTisk.getRacPozic()) {
                        if (p != null && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                            String lookup = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                            if (lookup != null && !lookup.trim().isEmpty()) {
                                p.setNaziv(lookup.trim());
                            }
                        }
                    }
                }

                final RacunTp finalRacunToPrint = racunZaTisk;

                mainHandler.post(() -> {
                    enableEkran();
                    if (getContext() != null) {
                        Toast.makeText(requireContext(), "Račun #" + finalRacunToPrint.getRacunId() + " (" + nacinNaziv + ") zaključen!", Toast.LENGTH_SHORT).show();
                    }

                    if (getContext() != null) {
                        BluetoothPrintHelper.printReceiptComplete(requireContext(), finalRacunToPrint, new BluetoothPrintHelper.OnPrintListener() {
                            @Override
                            public void onStart() {}

                            @Override
                            public void onSuccess(String message) {
                                if (getContext() != null) {
                                    // [DEBUG-TOAST] Toast.makeText(requireContext(), "Tisk: " + message, Toast.LENGTH_SHORT).show();
                                }
                            }

                            @Override
                            public void onError(String errorMessage) {
                                if (getContext() != null) {
                                    Toast.makeText(requireContext(), "Napaka tiskanja: " + errorMessage, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }

                    prefs.setActiveRacunId(0);
                    Globals.getInstance().setCurrentRacun(null);

                    if (Globals.getInstance().ispLogoutPoIzpisu()) {
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
                        }
                    } else if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).navigateToFragment(new MizeFragment());
                    }
                });

            } catch (VersionConflictException vce) {
                mainHandler.post(() -> handleVersionConflict(vce));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    enableEkran();
                    if (currentRacun != null && currentRacun.getRacPlaci() != null) {
                        currentRacun.getRacPlaci().remove(pl);
                        currentRacun.setStatus(1);
                        currentRacun.preracunajVsote();
                    }
                    String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "Neznana napaka";
                    Toast.makeText(requireContext(), "Napaka pri hitrem plačilu: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
