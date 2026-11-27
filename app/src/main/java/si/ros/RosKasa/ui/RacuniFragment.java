package si.ros.RosKasa.ui;

import android.content.Intent;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.R;
import si.ros.RosKasa.models.PlaciloTp;
import si.ros.RosKasa.models.SlipEmaTp;
import si.ros.RosKasa.payment.PaytenPaymentService;
import si.ros.RosKasa.payment.PaymentRecoveryManager;
import si.ros.RosKasa.payment.SixTapPaymentService;
import si.ros.RosKasa.payment.models.PaytenResponse;
import si.ros.RosKasa.payment.models.SixTapResponse;
import si.ros.RosKasa.models.PraviceConsts;
import si.ros.RosKasa.models.StornoResult;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.MainActivity;
import si.ros.RosKasa.databinding.FragmentRacuniBinding;
import si.ros.RosKasa.models.HitraTipkaTp;
import si.ros.RosKasa.models.PozicijaTp;
import si.ros.RosKasa.models.RacunSeznamItem;
import si.ros.RosKasa.models.RacunTp;
import si.ros.RosKasa.print.BluetoothPrintHelper;
import si.ros.RosKasa.print.RacunPrintBuilder;
import si.ros.RosKasa.soap.RosKasaSoapClient;

public class RacuniFragment extends Fragment implements RacunSeznamAdapter.OnItemClickListener {
    private static final String TAG = "RacuniFragment";

    private FragmentRacuniBinding binding;
    private AppPreferences prefs;
    private RacunSeznamAdapter adapter;

    private final List<RacunSeznamItem> allItems = new ArrayList<>();
    private final List<RacunSeznamItem> filteredItems = new ArrayList<>();

    // Default STATUS: 1 = ODPRTI, 2 = IZPISANI
    private int currentStatusFilter = 1;

    private final ActivityResultLauncher<String[]> bluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!result.containsValue(false)) {
                    startTestPrint();
                } else {
                    Toast.makeText(requireContext(), "Dovoljenje za Bluetooth je obvezno za tiskanje!", Toast.LENGTH_LONG).show();
                }
            });

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentRacuniBinding.inflate(inflater, container, false);
        prefs = new AppPreferences(requireContext());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupRecyclerView();
        setupNavigationButtons();
        setupSearch();

        updateToggleStatusButtonText();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadRacuniFromApi();
    }

    private void setupRecyclerView() {
        adapter = new RacunSeznamAdapter(this);
        binding.rvRacuni.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvRacuni.setAdapter(adapter);
    }

    private void setupNavigationButtons() {
        // Preklop statusa (Odprti STATUS=1 <-> Izpisani STATUS=2)
        binding.btnToggleStatus.setOnClickListener(v -> {
            currentStatusFilter = (currentStatusFilter == 1) ? 2 : 1;
            updateToggleStatusButtonText();
            loadRacuniFromApi();
        });

        // POS Navigacijske tipke
        binding.btnNavMize.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new MizeFragment());
            }
        });

        binding.btnNavNaroci.setOnClickListener(v -> {
            RacunSeznamItem selected = adapter.getSelectedItem();
            if (selected != null) {
                prefs.setActiveRacunId(selected.getRacunId());
                prefs.setActiveMarker(selected.getMarker());
                Globals.getInstance().setCurrentRacun(null);
            }
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
            }
        });

        binding.btnNavPlacila.setOnClickListener(v -> {
            RacunSeznamItem selected = adapter.getSelectedItem();
            if (selected != null) {
                prefs.setActiveRacunId(selected.getRacunId());
                prefs.setActiveMarker(selected.getMarker());
                Globals.getInstance().setCurrentRacun(null);
            }
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new PlacilaFragment());
            }
        });

        binding.btnOdjava.setVisibility(Globals.getInstance().ispLogout() ? View.VISIBLE : View.INVISIBLE);
        binding.btnOdjava.setOnClickListener(v -> {
            Globals.getInstance().setTekocaOseba(null);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToFragment(new LoginFragment());
            }
        });

        binding.btnStorno.setOnClickListener(v -> {
            RacunSeznamItem selected = adapter.getSelectedItem();
            int selId = selected != null ? selected.getRacunId() : 0;
            Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna sprožen (seznam) R:" + selId);
            if (!Globals.getInstance().isDovoljeno(PraviceConsts.SLahkoStorniraRacun)) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + selId + " razlog=ni pravice SLahkoStorniraRacun");
                Toast.makeText(requireContext(), "Nimate pravice za storno računa!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected == null) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN razlog=ni izbranega računa");
                Toast.makeText(requireContext(), "Prosim izberite račun za storno!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected.getStatus() == null || (selected.getStatus() != 2 && selected.getStatus() != 4)) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + selId + " razlog=status=" + selected.getStatus() + " (dovoljen le 2 ali 4)");
                Toast.makeText(requireContext(), "Stornirati je mogoče le zaključene račune (status 2 ali status 4)!", Toast.LENGTH_SHORT).show();
                return;
            }
            prikaziPredogledRacuna(selected);
        });

        binding.btnTestTiskanja.setOnClickListener(v -> checkPermissionsAndPrint());

        binding.btnInkaso.setOnClickListener(v -> izvediPrintInkaso());
        binding.btnObracun.setOnClickListener(v -> izvediObracun());
        binding.btnZdruzi.setOnClickListener(v -> izvediZdruziRacune());
        binding.btnDeli.setOnClickListener(v -> izvediSplitRacuna());
        binding.btnZamenjajMarker.setOnClickListener(v -> izvediZamenjajMarker());
        binding.btnObracunani.setOnClickListener(v -> izvediIskanjeObracunanegaRacuna());
        binding.btnPrikaz.setOnClickListener(v -> {
            RacunSeznamItem selected = adapter.getSelectedItem();
            if (selected == null) {
                Toast.makeText(requireContext(), "Prosim izberite račun za prikaz!", Toast.LENGTH_SHORT).show();
                return;
            }
            prikaziPredogledRacuna(selected);
        });

        binding.btnIzpisRacuna.setOnClickListener(v -> izvediIzpisIzbranegaRacuna());
    }

    /**
     * Izpis izbranega računa neposredno iz seznama računov:
     * - za STATUS == 1 dovoljen IZKLJUČNO, če je račun v celoti plačan (npr. nedokončan izpis / storno po napaki tiskalnika),
     * - za STATUS >= 2 zahteva pravico SLahkoPonovnoIzpiseRacun in poveča STKOPIJ na strežniku.
     */
    private void izvediIzpisIzbranegaRacuna() {
        RacunSeznamItem selected = adapter.getSelectedItem();
        int selId = selected != null ? selected.getRacunId() : 0;
        Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna sprožen (seznam) R:" + selId);

        if (selected == null) {
            Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN (seznam) razlog=ni izbranega računa");
            Toast.makeText(requireContext(), "Prosim izberite račun za izpis!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selected.getStatus() != null && selected.getStatus() >= 2
                && !Globals.getInstance().isDovoljeno(PraviceConsts.SLahkoPonovnoIzpiseRacun)) {
            Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN (seznam) R:" + selId + " razlog=ni pravice SLahkoPonovnoIzpiseRacun");
            Toast.makeText(requireContext(), "Nimate pravice za ponovni izpis računa!", Toast.LENGTH_LONG).show();
            return;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                RacunTp r = RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), selected.getRacunId());
                if (r == null) {
                    Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN (seznam) R:" + selId + " razlog=getRacun vrnil null");
                    mainHandler.post(() -> {
                        if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                        if (getContext() != null) {
                            Toast.makeText(requireContext(), "Računa #" + selId + " ni bilo mogoče naložiti s strežnika.", Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }

                r.preracunajVsote();
                if (r.getRacPozic() != null) {
                    for (PozicijaTp p : r.getRacPozic()) {
                        if (p != null && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                            String lookup = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                            if (lookup != null && !lookup.trim().isEmpty()) {
                                p.setNaziv(lookup.trim());
                            }
                        }
                    }
                }

                RacunTp racunToPrint = r;
                if (r.getStatus() == 1) {
                    boolean isStorno = (r.getStornoRacunId() != null && r.getStornoRacunId() > 0)
                            || (r.getZnesek() != null && r.getZnesek().compareTo(BigDecimal.ZERO) < 0);
                    boolean premaloPlacano = isStorno
                            ? (r.getPlacano().abs().compareTo(r.getZnesek().abs()) < 0)
                            : (r.getPlacano().compareTo(r.getZnesek()) < 0);
                    if (premaloPlacano) {
                        Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN (seznam) R:" + selId
                                + " razlog=STATUS=1 ni v celoti plačan zn=" + r.getZnesek() + " pl=" + r.getPlacano());
                        mainHandler.post(() -> {
                            if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                            if (getContext() != null) {
                                Toast.makeText(requireContext(),
                                        String.format(Locale.getDefault(),
                                                "Račun #%d ni v celoti plačan (Zn: %.2f € / Pl: %.2f €)!\nIzpis iz seznama je dovoljen le za v celoti plačane račune.",
                                                selId, r.getZnesek(), r.getPlacano()),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }

                    int mobileId = 0;
                    try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                    si.ros.RosKasa.models.GetRacunRsTp resp;
                    if (r.isZeFiskaliziran()) {
                        int novaKop = Math.max(1, r.getStKopij() != null ? r.getStKopij() : 1);
                        resp = RosKasaSoapClient.setRacunStatusOnly(prefs.getServerUrl(), prefs.getToken(), mobileId, r, 2, novaKop);
                        r.setStatus(2);
                        r.setStKopij(novaKop);
                    } else {
                        boolean imaFiskalno = false;
                        if (r.getRacPlaci() != null) {
                            for (PlaciloTp pl : r.getRacPlaci()) {
                                if (pl != null && !pl.isRowDeleted() && pl.getPlaciloId() != 99) {
                                    si.ros.RosKasa.models.NacPlacTp np = Globals.getInstance().getPlaciloById(pl.getPlaciloId());
                                    if (np != null && np.getFiskalno() != null && np.getFiskalno() == 1) {
                                        imaFiskalno = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (imaFiskalno) r.setFiskalizacija(1);
                        if (r.getRacPozic() != null) {
                            for (PozicijaTp p : r.getRacPozic()) {
                                if (p != null) p.setNeNarocaj(true);
                            }
                        }
                        r.setStatus(2);
                        r.setStKopij(Math.max(1, r.getStKopij() != null ? r.getStKopij() : 1));
                        resp = RosKasaSoapClient.setRacun(prefs.getServerUrl(), prefs.getToken(), mobileId, r);
                    }
                    if (resp != null && resp.getRacGlava() != null) {
                        RacunTp returned = resp.getRacGlava();
                        if ((returned.getRacPozic() == null || returned.getRacPozic().isEmpty()) && r.getRacPozic() != null) {
                            returned.setRacPozic(r.getRacPozic());
                        }
                        if (returned.getRacPlaci() == null && r.getRacPlaci() != null) {
                            returned.setRacPlaci(r.getRacPlaci());
                        }
                        if (returned.getFiskalizacija() == null && r.getFiskalizacija() != null) {
                            returned.setFiskalizacija(r.getFiskalizacija());
                        }
                        racunToPrint = returned;
                    }
                }

                final RacunTp finalToPrint = racunToPrint;
                mainHandler.post(() -> {
                    if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                    if (getContext() == null) return;
                    BluetoothPrintHelper.printReceiptComplete(requireContext(), finalToPrint, new BluetoothPrintHelper.OnPrintListener() {
                        @Override
                        public void onStart() {}

                        @Override
                        public void onSuccess(String message) {
                            if (getContext() != null) {
                                loadRacuniFromApi();
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            if (getContext() != null) {
                                Toast.makeText(requireContext(), "Napaka: " + errorMessage, Toast.LENGTH_LONG).show();
                                loadRacuniFromApi();
                            }
                        }
                    });
                });
            } catch (Exception e) {
                Globals.getInstance().vpisiNapakoKronologija("IzpisRacuna (seznam) R:" + selId, e);
                mainHandler.post(() -> {
                    if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                    if (getContext() != null) {
                        Toast.makeText(requireContext(), "Napaka pri izpisu računa #" + selId + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /**
     * Delphi FormKasaMobile.pas btnIzpisaniClick + getRacunIzbran:
     * Iskanje obračunanega računa (STATUS=4, ali tudi STATUS=2) po referenčni številki (RACUN_ID)
     * za daljše obdobje maxDniNazaj (privzeto 365 dni).
     */
    private void izvediIskanjeObracunanegaRacuna() {
        if (getContext() == null) return;
        if (!Globals.getInstance().isDovoljeno(PraviceConsts.SLahkoPonovnoIzpiseRacun)) {
            Toast.makeText(requireContext(), "Ni pravic za ponoven izpis računa!", Toast.LENGTH_LONG).show();
            return;
        }

        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Vpiši ref. št. računa");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Obračunani računi")
                .setMessage("Vpiši ref. št. računa (obdobje zadnjih " + Globals.getInstance().getMaxDniNazaj() + " dni):")
                .setView(container)
                .setPositiveButton("Išči", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (val.isEmpty()) return;
                    int pomRacunId;
                    try {
                        pomRacunId = Integer.parseInt(val);
                    } catch (NumberFormatException ex) {
                        Toast.makeText(requireContext(), "Neveljavna številka računa!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (pomRacunId <= 0) return;

                    final int maxDni = Globals.getInstance().getMaxDniNazaj();
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_MONTH, -maxDni);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.getDefault());
                    final String odDatum = sdf.format(cal.getTime());

                    Globals g = Globals.getInstance();
                    final int strmId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0)
                            ? g.getTocilnicaId() : prefs.getTocilnicaId();

                    binding.pbLoading.setVisibility(View.VISIBLE);
                    executor.execute(() -> {
                        try {
                            RacunTp r = RosKasaSoapClient.getRacun(
                                    prefs.getServerUrl(),
                                    prefs.getToken(),
                                    pomRacunId,
                                    strmId,
                                    odDatum
                            );
                            mainHandler.post(() -> {
                                if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                                if (getContext() == null) return;
                                if (r != null && r.getRacunId() > 0) {
                                    RacunSeznamItem foundItem = new RacunSeznamItem(
                                            r.getRacunId(),
                                            r.getKasiral(),
                                            r.getMarker() != null ? r.getMarker() : "",
                                            r.getStatus(),
                                            r.getStornoRacunId(),
                                            r.getZnesek()
                                    );
                                    prikaziPredogledRacuna(foundItem, r);
                                } else {
                                    Toast.makeText(requireContext(),
                                            "Račun #" + pomRacunId + " ni bil najden (zadnjih " + maxDni + " dni).",
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                                if (getContext() != null) {
                                    Toast.makeText(requireContext(),
                                            "Napaka pri iskanju računa #" + pomRacunId + ": " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("Prekliči", null)
                .show();
    }

    private void checkPermissionsAndPrint() {
        // Opomba po ToDoPayment: v primeru težav, da se blagajna 'zacikla' pri Worldline TapOn, gumb Test tiskanja počisti shranjene vrednosti
        si.ros.RosKasa.payment.PaymentRecoveryManager.clearAllRecoveryData(requireContext());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {

                bluetoothPermissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
                return;
            }
        }
        startTestPrint();
    }

    private void startTestPrint() {
        BluetoothPrintHelper.printTestReceipt(requireContext(), new BluetoothPrintHelper.OnPrintListener() {
            @Override
            public void onStart() {
                Toast.makeText(requireContext(), "Zapenjam Bluetooth tiskanje...", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSuccess(String message) {
                if (getContext() != null) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                if (getContext() != null) {
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void updateToggleStatusButtonText() {
        if (currentStatusFilter == 1) {
            binding.btnToggleStatus.setText(R.string.btn_toggle_status_izpisani);
        } else {
            binding.btnToggleStatus.setText(R.string.btn_toggle_status_odprti);
        }
    }

    private void setupSearch() {
        binding.etSearchRacun.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private String calculateOdDatum() {
        Calendar cal = Calendar.getInstance();
        int dniNazaj = 3;
        if (currentStatusFilter == 2 && Globals.getInstance().getIzpisaniVidniDo() > 0) {
            dniNazaj = Globals.getInstance().getIzpisaniVidniDo();
        }
        cal.add(Calendar.DAY_OF_MONTH, -dniNazaj);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.getDefault());
        return sdf.format(cal.getTime());
    }

    private void loadRacuniFromApi() {
        String odDatum = calculateOdDatum();

        if (!prefs.isRegistered()) {
            binding.pbLoading.setVisibility(View.GONE);
            allItems.clear();
            applyFilters();
            return;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);
        binding.tvEmptyList.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                Globals g = Globals.getInstance();
                // Ločitev privilegijev (enako kot Delphi FormKasaMobile.pas:1120-1137):
                // - "Vidi vse račune": vidi vse STATUS=1 IN vse STATUS=2/4
                // - "Vidi Vsa Narocila": vidi vse STATUS=1 IN samo SVOJE STATUS=2/4
                boolean vidiVse = (currentStatusFilter == 1)
                        ? (g.isDovoljeno(PraviceConsts.SVidiVseRacune) || g.isDovoljeno(PraviceConsts.SVidiVsaNarocila) || g.isOdpriVseRacune())
                        : (g.isDovoljeno(PraviceConsts.SVidiVseRacune) || g.isOdpriVseRacune());
                int pOseba = vidiVse ? 0 : g.getTekocaOsebaId();
                int strmId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0)
                        ? g.getTocilnicaId() : prefs.getTocilnicaId();

                // Klic WSDL getRacuniSeznam s parametri STATUS, OD_DATUM, OSEBA_ID in STRM_ID
                List<RacunSeznamItem> result = RosKasaSoapClient.getRacuniSeznam(
                        prefs.getServerUrl(),
                        prefs.getToken(),
                        prefs.getMobileId(),
                        currentStatusFilter,
                        odDatum,
                        pOseba,
                        strmId
                );

                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    allItems.clear();
                    if (!result.isEmpty()) {
                        allItems.addAll(result);
                    }
                    applyFilters();
                });
            } catch (Exception e) {
                Globals.getInstance().vpisiNapakoKronologija("Racuni loadRacuniFromServer", e);
                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    allItems.clear();
                    applyFilters();
                });
            }
        });
    }

    private void applyFilters() {
        filteredItems.clear();
        String query = binding.etSearchRacun.getText().toString().trim().toLowerCase();

        for (RacunSeznamItem item : allItems) {
            boolean isOpenStatus1 = (item.getStatus() != null && item.getStatus() == 1);
            boolean isStornoOrNegative = (item.getStornoRacunId() != null && item.getStornoRacunId() > 0)
                    || (item.getStornoOriginal() != null && item.getStornoOriginal() > 0)
                    || (item.getZnesek() != null && item.getZnesek().compareTo(BigDecimal.ZERO) < 0);

            // Izpisani (STATUS != 1) storno računi so skriti, razen ob eksplicitnem iskanju po ID.
            // Odprti (STATUS == 1) storno računi (npr. po napaki tiskalnika) pa MORAJO biti vidni v seznamu, da se jih lahko izpiše!
            if (isStornoOrNegative && !isOpenStatus1) {
                if (!query.isEmpty() && String.valueOf(item.getRacunId()).contains(query)) {
                    // Dovoljen prikaz storno računa ob eksplicitnem iskanju
                } else {
                    continue;
                }
            }

            // STATUS filtriranje: če je izbran prikaz izpisanih računov (filter 2), vključimo tako status 2 kot status 4 (obračunan)
            boolean statusMatch = false;
            if (item.getStatus() != null) {
                if (currentStatusFilter == 2) {
                    statusMatch = (item.getStatus() == 2 || item.getStatus() == 4);
                } else {
                    statusMatch = (item.getStatus() == currentStatusFilter);
                }
            }
            if (statusMatch) {
                if (query.isEmpty()) {
                    filteredItems.add(item);
                } else {
                    String racunIdStr = String.valueOf(item.getRacunId());
                    String marker = item.getMarker() != null ? item.getMarker().toLowerCase() : "";
                    if (racunIdStr.contains(query) || marker.contains(query)) {
                        filteredItems.add(item);
                    }
                }
            }
        }

        // Izpisani računi: uredi sort descending po številki računa
        if (currentStatusFilter == 2) {
            filteredItems.sort((a, b) -> Integer.compare(b.getRacunId(), a.getRacunId()));
        }

        adapter.setItems(filteredItems);
        binding.tvEmptyList.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onItemClick(RacunSeznamItem item, int position) {
        if (item != null) {
            prefs.setActiveRacunId(item.getRacunId());
            prefs.setActiveMarker(item.getMarker());

            // Če gre za zaključen račun (STATUS = 2 ali 4), odpri tekstovni predogled
            if (item.getStatus() != null && (item.getStatus() == 2 || item.getStatus() == 4)) {
                prikaziPredogledRacuna(item);
            } else {
                Globals.getInstance().setCurrentRacun(null);
                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Izbran odprt račun #" + item.getRacunId() + " (Miza: " + item.getMarker() + ")", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void prikaziPredogledRacuna(RacunSeznamItem item) {
        prikaziPredogledRacuna(item, null);
    }

    private void prikaziPredogledRacuna(RacunSeznamItem item, @Nullable RacunTp preloadedRacun) {
        if (getContext() == null) return;

        Dialog dialog = new Dialog(requireContext());
        dialog.setContentView(R.layout.dialog_racun_preview);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = dialog.findViewById(R.id.tvPreviewTitle);
        TextView tvText = dialog.findViewById(R.id.tvPreviewText);
        ProgressBar pbLoading = dialog.findViewById(R.id.pbPreviewLoading);
        MaterialButton btnZapri = dialog.findViewById(R.id.btnPreviewZapri);
        MaterialButton btnStorno = dialog.findViewById(R.id.btnPreviewStorno);
        MaterialButton btnNatisni = dialog.findViewById(R.id.btnPreviewNatisni);

        tvTitle.setText(getString(R.string.preview_racun_title, item.getRacunId()));
        tvText.setText(R.string.preview_loading_data);
        pbLoading.setVisibility(View.VISIBLE);
        btnNatisni.setEnabled(false);
        btnStorno.setEnabled(false);

        btnZapri.setOnClickListener(v -> dialog.dismiss());

        final RacunTp[] racunHolder = new RacunTp[1];
        final int[] stKopijHolder = new int[]{1};

        executor.execute(() -> {
            try {
                RacunTp r = (preloadedRacun != null)
                        ? preloadedRacun
                        : RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), item.getRacunId());
                if (r != null) {

                    // Zagotovi, da je cenik naložen v predpomnilnik za lookup nazivov
                    if (!Globals.getInstance().hasCachedCenik()) {
                        int strmId = (Globals.getInstance().getTocilnicaId() != null && Globals.getInstance().getTocilnicaId() > 0)
                                ? Globals.getInstance().getTocilnicaId()
                                : (prefs.getTocilnicaId() > 0 ? prefs.getTocilnicaId() : prefs.getHisObrat());
                        try {
                            List<CenikListAdapter.CenikItem> cenik = RosKasaSoapClient.getCenik(prefs.getServerUrl(), prefs.getToken(), strmId);
                            if (!cenik.isEmpty()) {
                                Globals.getInstance().setCachedCenik(cenik);
                            }
                        } catch (Exception ignored) {}
                    }
                    if (!Globals.getInstance().hasCachedHitreTipke() && prefs.getTipkePosId() > 0) {
                        try {
                            List<HitraTipkaTp> tipke = RosKasaSoapClient.getHitreTipke(prefs.getServerUrl(), prefs.getToken(), prefs.getTipkePosId());
                            if (!tipke.isEmpty()) {
                                Globals.getInstance().setCachedHitreTipke(tipke);
                            }
                        } catch (Exception ignored) {}
                    }

                    // Preveri manjkajoče nazive artiklov
                    if (r.getRacPozic() != null) {
                        for (PozicijaTp p : r.getRacPozic()) {
                            if (p != null && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                                String lookupName = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                                if (lookupName != null && !lookupName.trim().isEmpty()) {
                                    p.setNaziv(lookupName.trim());
                                }
                            }
                        }
                    }

                    racunHolder[0] = r;
                    int k = (r.getStKopij() != null && r.getStKopij() > 0) ? r.getStKopij() + 1 : 1;
                    stKopijHolder[0] = k;

                    RacunPrintBuilder.ReceiptResult printRes =
                            RacunPrintBuilder.buildReceipt(r, Globals.getInstance(), k);

                    mainHandler.post(() -> {
                        pbLoading.setVisibility(View.GONE);
                        btnNatisni.setEnabled(true);
                        btnStorno.setEnabled(true);
                        tvText.setText(printRes.getTextPreview());
                    });
                } else {
                    mainHandler.post(() -> {
                        pbLoading.setVisibility(View.GONE);
                        btnNatisni.setEnabled(false);
                        btnStorno.setEnabled(false);
                        tvText.setText(R.string.preview_error_load);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    btnNatisni.setEnabled(false);
                    btnStorno.setEnabled(false);
                    tvText.setText(getString(R.string.preview_error_read, e.getMessage()));
                });
            }
        });

        btnNatisni.setOnClickListener(v -> {
            if (racunHolder[0] == null) {
                Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN (predogled) R:" + item.getRacunId() + " razlog=racunHolder je null");
                return;
            }
            final RacunTp r = racunHolder[0];
            Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna sprožen (predogled) R:" + r.getRacunId()
                    + " status=" + r.getStatus() + " zn=" + r.getZnesek() + " stornoId=" + r.getStornoRacunId());
            if (r.getStatus() >= 2 && !Globals.getInstance().isDovoljeno(PraviceConsts.SLahkoPonovnoIzpiseRacun)) {
                Globals.getInstance().vpisiKronologijoDebugL1("IzpisRacuna BLOKIRAN R:" + r.getRacunId() + " razlog=ni pravice SLahkoPonovnoIzpiseRacun");
                Toast.makeText(requireContext(), "Nimate pravice za ponovni izpis računa!", Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();

            final int stKopij = stKopijHolder[0];

            final RacunTp[] racunToPrintHolder = new RacunTp[]{r};

            binding.pbLoading.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                // Pravilo: Vedno, ko izpisujemo račun, ki ima status=1 (tudi nedokončan storno račun), ga postavimo v status=2 na strežniku
                if (r.getStatus() == 1) {
                    try {
                        int mobileId = 0;
                        try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                        si.ros.RosKasa.models.GetRacunRsTp resp;
                        if (r.isZeFiskaliziran()) {
                            // Račun je ŽE fiskaliziran -> spremeni izključno STATUS=2 in STKOPIJ!
                            int novaKop = Math.max(1, stKopij);
                            resp = RosKasaSoapClient.setRacunStatusOnly(prefs.getServerUrl(), prefs.getToken(), mobileId, r, 2, novaKop);
                            r.setStatus(2);
                            r.setStKopij(novaKop);
                        } else {
                            boolean imaFiskalno = false;
                            if (r.getRacPlaci() != null) {
                                for (PlaciloTp pl : r.getRacPlaci()) {
                                    if (pl != null && !pl.isRowDeleted() && pl.getPlaciloId() != 99) {
                                        si.ros.RosKasa.models.NacPlacTp np = Globals.getInstance().getPlaciloById(pl.getPlaciloId());
                                        if (np != null && np.getFiskalno() != null && np.getFiskalno() == 1) {
                                            imaFiskalno = true;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (imaFiskalno) {
                                r.setFiskalizacija(1);
                            }
                            if (r.getRacPozic() != null) {
                                for (PozicijaTp p : r.getRacPozic()) {
                                    if (p != null) p.setNeNarocaj(true);
                                }
                            }
                            r.setStatus(2);
                            r.setStKopij(Math.max(1, stKopij));
                            resp = RosKasaSoapClient.setRacun(prefs.getServerUrl(), prefs.getToken(), mobileId, r);
                        }
                        if (resp != null && resp.getRacGlava() != null) {
                            RacunTp returned = resp.getRacGlava();
                            if ((returned.getRacPozic() == null || returned.getRacPozic().isEmpty()) && r.getRacPozic() != null) {
                                returned.setRacPozic(r.getRacPozic());
                            }
                            if (returned.getRacPlaci() == null && r.getRacPlaci() != null) {
                                returned.setRacPlaci(r.getRacPlaci());
                            }
                            if (returned.getFiskalizacija() == null && r.getFiskalizacija() != null) {
                                returned.setFiskalizacija(r.getFiskalizacija());
                            }
                            racunToPrintHolder[0] = returned;
                        }
                        Globals.getInstance().vpisiKronologijoDebugL1(prefs.getServerUrl(), prefs.getToken(), prefs.getMobileId(),
                                "UpdateInvoiceStatus R:" + r.getRacunId() + " na status 2 STKOPIJ:" + r.getStKopij(),
                                Globals.getInstance().getTekocaOsebaId(), Globals.getInstance().getTocilnicaId());
                    } catch (Exception exStatus) {
                        Log.e("RacuniFragment", "Napaka pri posodobitvi statusa 2 za račun #" + r.getRacunId(), exStatus);
                        Globals.getInstance().vpisiNapakoKronologija("IzpisRacuna setRacun R:" + r.getRacunId(), exStatus);
                        mainHandler.post(() -> {
                            if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                            if (getContext() != null) {
                                Toast.makeText(requireContext(), "Napaka pri zaključku računa #" + r.getRacunId() + ": " + exStatus.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                }
                // Opomba: za STATUS >= 2 (ponovni izpis kopije) bo PrintDataHandler / BluetoothPrintHelper.sendRacIzpisanNaServer
                // izračunal konStKopij = zacStKopij + zaPrintStKopij in ga posodobil na strežniku preko setRacunStatusOnly,
                // zato STKOPIJ tukaj ne povečujemo dvakrat.

                mainHandler.post(() -> {
                    if (binding != null) binding.pbLoading.setVisibility(View.GONE);
                    if (getContext() == null) return;

                    // [DEBUG-TOAST] Toast.makeText(requireContext(), "Tiskanje računa #" + item.getRacunId() + "...", Toast.LENGTH_SHORT).show();
                    BluetoothPrintHelper.printReceiptComplete(requireContext(), racunToPrintHolder[0], new BluetoothPrintHelper.OnPrintListener() {
                        @Override
                        public void onStart() {}

                        @Override
                        public void onSuccess(String message) {
                            if (getContext() != null) {
                                // [DEBUG-TOAST] Toast.makeText(requireContext(), "Tisk: " + message, Toast.LENGTH_SHORT).show();
                                loadRacuniFromApi();
                            }
                        }

                        @Override
                        public void onError(String errorMessage) {
                            if (getContext() != null) {
                                Toast.makeText(requireContext(), "Napaka: " + errorMessage, Toast.LENGTH_LONG).show();
                                loadRacuniFromApi();
                            }
                        }
                    });
                });
            });
        });

        btnStorno.setOnClickListener(v -> {
            Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna sprožen (predogled) R:" + item.getRacunId());
            if (!Globals.getInstance().isDovoljeno(PraviceConsts.SLahkoStorniraRacun)) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + item.getRacunId() + " razlog=ni pravice SLahkoStorniraRacun");
                Toast.makeText(requireContext(), "Nimate pravice za storno računa!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (item.getStatus() == null || (item.getStatus() != 2 && item.getStatus() != 4)) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + item.getRacunId() + " razlog=status=" + item.getStatus());
                Toast.makeText(requireContext(), "Stornirati je mogoče le zaključene račune (status 2 ali status 4)!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (racunHolder[0] == null) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + item.getRacunId() + " razlog=podatki se še nalagajo");
                Toast.makeText(requireContext(), "Podatki računa se še nalagajo...", Toast.LENGTH_SHORT).show();
                return;
            }

            android.bluetooth.BluetoothAdapter bAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (!BluetoothPrintHelper.hasBluetoothPermissions(requireContext()) || bAdapter == null || !bAdapter.isEnabled()) {
                Globals.getInstance().vpisiKronologijoDebugL1("StornoRacuna BLOKIRAN R:" + item.getRacunId() + " razlog=Bluetooth izklopljen ali brez dovoljenj");
                Toast.makeText(requireContext(), "Bluetooth ni vklopljen ali nimate ustreznih dovoljenj!", Toast.LENGTH_LONG).show();
                return;
            }

            String sporocilo = "Storno računa #" + item.getRacunId() + ", " + item.getFormattedZnesek() + "\n\nAli želite nadaljevati?";
            new AlertDialog.Builder(requireContext())
                    .setTitle("Storno računa")
                    .setMessage(sporocilo)
                    .setPositiveButton("Da", (d, w) -> {
                        dialog.dismiss();

                        boolean preprecimEdit = Globals.getInstance().isDovoljeno(PraviceConsts.SpreprecimEditStornoPozicij);
                        if (preprecimEdit) {
                            // Če ima uporabnik nastavljeno SpreprecimEditStornoPozicij se avtomatsko naredi novo naročilo brez možnosti urejanja
                            izvediStornoRacuna(item, racunHolder[0], null, true, 0);
                        } else {
                            // Odpremo dialog za izbiro storno razloga in vprašanje za nov račun
                            StornoRazlogDialog.show(requireContext(), prefs.getServerUrl(), prefs.getToken(), (stornoRazlogId, stornoRazlogNaziv, novoNarocilo) -> {
                                izvediStornoRacuna(item, racunHolder[0], stornoRazlogId, novoNarocilo, 1);
                            });
                        }
                    })
                    .setNegativeButton("Ne", null)
                    .show();
        });

        dialog.show();
    }

    private void izvediStornoRacuna(RacunSeznamItem item, RacunTp racun, Integer stornoRazlogId, boolean novoNarocilo, int urejamStorno) {
        if (getContext() == null) return;

        // Preveri ali racun vsebuje karticno/POS placilo za POS storno (Delphi stornoExecute: samo za 1 < STATUS < 4; za STATUS >= 4 se POS void/refund NE izvaja)
        int racunStatus = (racun != null && racun.getStatus() > 0)
                ? racun.getStatus()
                : (item.getStatus() != null ? item.getStatus() : 0);
        boolean allowPosStorno = racunStatus > 1 && racunStatus < 4;

        PlaciloTp posPlacilo = null;
        if (allowPosStorno && racun != null && racun.getRacPlaci() != null) {
            for (PlaciloTp p : racun.getRacPlaci()) {
                if (p != null && !p.isRowDeleted()) {
                    int metoda = Globals.getInstance().placilometoda(p.getPlaciloId());
                    if (metoda == 14 || p.getPlaciloId() == 2 || (p.getMRef() != null && !p.getMRef().trim().isEmpty())) {
                        posPlacilo = p;
                        break;
                    }
                }
            }
        }

        final boolean isSixTapActive = Globals.getInstance().isSixTap();
        final boolean isPaytenActive = Globals.getInstance().isPayTenA();

        if (posPlacilo != null && (posPlacilo.getMRef() == null || posPlacilo.getMRef().trim().isEmpty())) {
            // Poskusi pridobiti M_REF iz SLIP_EMA pred stornom
            final PlaciloTp finalPosPlacilo = posPlacilo;
            Toast.makeText(requireContext(), "Preverjanje POS transakcije za storno...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                try {
                    List<SlipEmaTp> slips = RosKasaSoapClient.getSlipEma2(prefs.getServerUrl(), prefs.getToken(), item.getRacunId());
                    if (slips != null && !slips.isEmpty()) {
                        for (SlipEmaTp s : slips) {
                            if (s != null && s.getAcqTransRef() != null && !s.getAcqTransRef().trim().isEmpty()) {
                                finalPosPlacilo.setMRef(s.getAcqTransRef().trim());
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    final String fetchedRef = finalPosPlacilo.getMRef() != null ? finalPosPlacilo.getMRef().trim() : "";
                    if (isSixTapActive && !fetchedRef.isEmpty()) {
                        izvediSixTapStornoPredStornoRacuna(item, racun, finalPosPlacilo, stornoRazlogId, novoNarocilo, urejamStorno);
                    } else if (isPaytenActive && !fetchedRef.isEmpty()) {
                        izvediPaytenStornoPredStornoRacuna(item, racun, finalPosPlacilo, stornoRazlogId, novoNarocilo, urejamStorno);
                    } else {
                        izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno);
                    }
                });
            });
            return;
        }

        final String mRef = (posPlacilo != null && posPlacilo.getMRef() != null) ? posPlacilo.getMRef().trim() : "";

        if (posPlacilo != null && isSixTapActive && !mRef.isEmpty()) {
            izvediSixTapStornoPredStornoRacuna(item, racun, posPlacilo, stornoRazlogId, novoNarocilo, urejamStorno);
            return;
        } else if (posPlacilo != null && isPaytenActive && !mRef.isEmpty()) {
            izvediPaytenStornoPredStornoRacuna(item, racun, posPlacilo, stornoRazlogId, novoNarocilo, urejamStorno);
            return;
        }

        izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno);
    }

    private void izvediSixTapStornoPredStornoRacuna(RacunSeznamItem item, RacunTp racun, PlaciloTp pl,
                                                    Integer stornoRazlogId, boolean novoNarocilo, int urejamStorno) {
        if (!(getActivity() instanceof MainActivity)) {
            izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno);
            return;
        }
        MainActivity activity = (MainActivity) getActivity();

        final int racunId = item.getRacunId();
        final int pozicijaId = pl.getPozicijaId();
        final BigDecimal znesek = (pl.getDelniZnesek() != null && pl.getDelniZnesek().compareTo(BigDecimal.ZERO) > 0)
                ? pl.getDelniZnesek()
                : (pl.getZnesek() != null ? pl.getZnesek() : (racun.getZnesek() != null ? racun.getZnesek() : BigDecimal.ZERO));
        final BigDecimal napitnina = pl.getNapitnina() != null ? pl.getNapitnina() : BigDecimal.ZERO;
        final BigDecimal skupajZnesek = znesek.add(napitnina);
        final String mRef = pl.getMRef();
        final String wpiSessionId = pl.getWpiSessionId();

        final String op = (Globals.getInstance().getZadnjiSixRacunId() == racunId)
                ? SixTapPaymentService.OP_CANCEL_PAYMENT
                : SixTapPaymentService.OP_REFUND;

        Intent sixTapIntent = SixTapPaymentService.buildPaymentIntent(requireContext(), racunId, pozicijaId, skupajZnesek, napitnina, op, mRef, wpiSessionId);
        Toast.makeText(requireContext(), "Storniranje POS transakcije (Worldline Tap On)...", Toast.LENGTH_SHORT).show();

        activity.launchSixTap(sixTapIntent, new MainActivity.PaymentResultListener() {
            @Override
            public void onPaytenResult(int resultCode, Intent data) {}

            @Override
            public void onSixTapResult(int resultCode, Intent data) {
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    SixTapResponse resp = SixTapPaymentService.parseResponseIntent(data);
                    if (resp.isSuccess()) {
                        String sessionId = resp.getpWpiSessionId() != null ? resp.getpWpiSessionId() : (Globals.getInstance().getZadnjiWpiSessionId() != null ? Globals.getInstance().getZadnjiWpiSessionId() : "");
                        String errCond = resp.getErrorCondition() != null && !resp.getErrorCondition().isEmpty() ? resp.getErrorCondition() : "WPI_ERR_COND_NONE";
                        String remark = resp.getRemark() != null ? resp.getRemark() : "";
                        String znesekStr = String.format(Locale.GERMANY, "%.2f", skupajZnesek).replace(",00", ",0");
                        String authAmt = resp.getAuthorizedAmount() != null ? resp.getAuthorizedAmount() : "";
                        String tipAmt = resp.getTipAmount() != null ? resp.getTipAmount() : "";
                        String pSolRef = resp.getPaymentSolutionReference() != null ? resp.getPaymentSolutionReference() : "";

                        // SIXTAP RESULT OK
                        Globals.getInstance().vpisiKronologijo("SIXTAP RESULT OK R:" + racunId + " LokalReference R:" + mRef + " SIXTAP " + op + " result: WPI_RESULT_SUCCESS Error: " + errCond + " za M_REF: " + pSolRef + " remark: " + remark + " SESSION_ID: " + sessionId);

                        // SIXTAP WPI_RESULT_SUCCESS
                        Globals.getInstance().vpisiKronologijo("SIXTAP WPI_RESULT_SUCCESS za  R:" + racunId + " reference: " + mRef + " paymentSolutionReference: " + pSolRef + " authorizedAmount: " + authAmt + " tipAmount: " + tipAmt + " Z:" + znesekStr);

                        // SIXTAP setSlipEma
                        Globals.getInstance().vpisiKronologijo("SIXTAP setSlipEma R:" + racunId + " M_REF: " + pSolRef + " Op: " + op);

                        // SIXTAP ClearPendingPayment
                        Globals.getInstance().vpisiKronologijo("SIXTAP ClearPendingPayment ReqEmaAfterRefund=" + pozicijaId + "  R:" + racunId);

                        executor.execute(() -> {
                            try {
                                String serverUrl = prefs.getServerUrl();
                                String token = prefs.getToken();

                                SlipEmaTp slip = new SlipEmaTp();
                                slip.setStevilkaRacuna(racunId);
                                slip.setPozicijaId(pozicijaId);
                                slip.setSlipPrint(resp.getClient() != null ? resp.getClient() : "");
                                slip.setSlipPrints(resp.getMerchant() != null ? resp.getMerchant() : "");
                                slip.setStevilkaKartice(resp.getBrandName() != null ? resp.getBrandName() : (resp.getCardnumber() != null ? resp.getCardnumber() : ""));
                                slip.setUspelo("DA");
                                slip.setProjektId(3);
                                slip.setStType(op);
                                slip.setZnesek(znesek.negate());
                                slip.setZnesekSlip(resp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0 ? resp.getZnesekPOS().negate() : skupajZnesek.negate());
                                slip.setAcqTransRef(resp.getPaymentSolutionReference() != null ? resp.getPaymentSolutionReference() : mRef);
                                slip.setAppIdentifier(resp.getApplicationIdentifier() != null ? resp.getApplicationIdentifier() : "");
                                slip.setAcqReference(resp.getAcqreference() != null ? resp.getAcqreference() : mRef);
                                slip.setAuthReference(resp.getAcqreference() != null ? resp.getAcqreference() : mRef);
                                slip.setAvtorizacija("REF");

                                RosKasaSoapClient.setSlipEma(serverUrl, token, slip);
                                PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                            } catch (Exception e) {
                                Log.w(TAG, "setSlipEma storno racuna napaka: " + e.getMessage());
                            }
                            mainHandler.post(() -> izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno));
                        });
                    } else {
                        PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                        String sessionId = resp.getpWpiSessionId() != null ? resp.getpWpiSessionId() : "";
                        Globals.getInstance().vpisiKronologijo("SIXTAP RESULT OK R:" + racunId + " LokalReference R:" + mRef + " SIXTAP " + op + " result: WPI_RESULT_FAILURE Error: " + resp.getErrorCondition() + " za M_REF:  remark: " + resp.getRemark() + " SESSION_ID: " + sessionId);
                        Globals.getInstance().vpisiKronologijo("SIXTAP ClearPendingPayment ReqEmaAfterRefund=" + pozicijaId + " Error:" + resp.getErrorCondition() + "  R:" + racunId);

                        new AlertDialog.Builder(requireContext())
                                .setTitle("Napaka storno SixTap")
                                .setMessage("Storno na POS terminalu ni uspel: " + resp.getErrorCondition() + " " + resp.getRemark() + "\n\nRačun NI bil storniran!")
                                .setPositiveButton("V redu", null)
                                .show();
                    }
                } else {
                    PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                    Globals.getInstance().vpisiKronologijo("SIXTAP ClearPendingPayment ReqEmaAfterRefund=" + pozicijaId + " Preklic_uporabnika  R:" + racunId);
                    Toast.makeText(requireContext(), "Storno na POS terminalu prekinjen. Račun NI bil storniran.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void izvediPaytenStornoPredStornoRacuna(RacunSeznamItem item, RacunTp racun, PlaciloTp pl,
                                                    Integer stornoRazlogId, boolean novoNarocilo, int urejamStorno) {
        if (!(getActivity() instanceof MainActivity)) {
            izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno);
            return;
        }
        MainActivity activity = (MainActivity) getActivity();

        final int racunId = item.getRacunId();
        final int pozicijaId = pl.getPozicijaId();
        final BigDecimal znesek = (pl.getDelniZnesek() != null && pl.getDelniZnesek().compareTo(BigDecimal.ZERO) > 0)
                ? pl.getDelniZnesek()
                : (pl.getZnesek() != null ? pl.getZnesek() : (racun.getZnesek() != null ? racun.getZnesek() : BigDecimal.ZERO));
        final BigDecimal napitnina = pl.getNapitnina() != null ? pl.getNapitnina() : BigDecimal.ZERO;
        final BigDecimal skupajZnesek = znesek.add(napitnina);
        final String mRef = pl.getMRef();

        Intent paytenIntent = PaytenPaymentService.buildPaymentIntent(requireContext(), racunId, pozicijaId, skupajZnesek, "void", mRef);
        Toast.makeText(requireContext(), "Storniranje POS transakcije (PayTen void)...", Toast.LENGTH_SHORT).show();

        activity.launchPayten(paytenIntent, new MainActivity.PaymentResultListener() {
            @Override
            public void onPaytenResult(int resultCode, Intent data) {
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    PaytenResponse resp = PaytenPaymentService.parseResponseIntent(data);
                    if (resp.isSuccess()) {
                        Globals.getInstance().vpisiKronologijo("PAYTEN RESULT OK R:" + racunId + " void M_REF:" + resp.getPaymentSolutionReference() + " Auth:" + resp.getpAuthorizationCode());
                        executor.execute(() -> {
                            try {
                                String serverUrl = prefs.getServerUrl();
                                String token = prefs.getToken();

                                SlipEmaTp slip = new SlipEmaTp();
                                slip.setStevilkaRacuna(racunId);
                                slip.setPozicijaId(pozicijaId);
                                slip.setSlipPrint(resp.getClient() != null ? resp.getClient() : "");
                                slip.setSlipPrints(resp.getReceipt() != null ? resp.getReceipt() : "");
                                slip.setStevilkaKartice(resp.getCardNumber() != null ? resp.getCardNumber() : "");
                                slip.setUspelo("DA");
                                slip.setProjektId(3);
                                slip.setStType("void");
                                slip.setZnesek(znesek.negate());
                                slip.setZnesekSlip(resp.getZnesekPOS().compareTo(BigDecimal.ZERO) > 0 ? resp.getZnesekPOS().negate() : skupajZnesek.negate());
                                slip.setAcqTransRef(resp.getPaymentSolutionReference() != null ? resp.getPaymentSolutionReference() : mRef);
                                slip.setAppIdentifier(resp.getApplicationIdentifier() != null ? resp.getApplicationIdentifier() : "");
                                slip.setAcqReference(resp.getpAuthorizationCode() != null ? resp.getpAuthorizationCode() : mRef);
                                slip.setAuthReference(resp.getpAuthorizationCode() != null ? resp.getpAuthorizationCode() : mRef);
                                slip.setAvtorizacija("REF");

                                RosKasaSoapClient.setSlipEma(serverUrl, token, slip);
                                PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                            } catch (Exception e) {
                                Log.w(TAG, "setSlipEma Payten storno racuna napaka: " + e.getMessage());
                            }
                            mainHandler.post(() -> izvediStornoRacunaNaStrezniku(item, racun, stornoRazlogId, novoNarocilo, urejamStorno));
                        });
                    } else {
                        PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                        Globals.getInstance().vpisiKronologijo("PAYTEN RESULT FAIL R:" + racunId + " void Code:" + resp.getCode() + " Msg:" + resp.getMessage());
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Napaka storno PayTen")
                                .setMessage("Storno na POS terminalu ni uspel: " + resp.getCode() + " " + resp.getMessage() + "\n\nRačun NI bil storniran!")
                                .setPositiveButton("V redu", null)
                                .show();
                    }
                } else {
                    PaymentRecoveryManager.clearAllRecoveryData(requireContext());
                    Globals.getInstance().vpisiKronologijo("PAYTEN void prekinjen R:" + racunId);
                    Toast.makeText(requireContext(), "Storno PayTen prekinjen. Račun NI bil storniran.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onSixTapResult(int resultCode, Intent data) {}
        });
    }

    private void izvediStornoRacunaNaStrezniku(RacunSeznamItem item, RacunTp racun, Integer stornoRazlogId, boolean novoNarocilo, int urejamStorno) {
        if (getContext() == null) return;

        int racunId = item.getRacunId();
        int verzija = racun != null ? racun.getVerzijaZapisa() : 0;
        int osebaId = Globals.getInstance().getTekocaOsebaId();
        if (osebaId <= 0 && racun != null && racun.getKasiral() != null) {
            osebaId = racun.getKasiral();
        }
        if (osebaId <= 0) {
            osebaId = 1;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);
        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Izvajam storno računa #" + racunId + "...", Toast.LENGTH_SHORT).show();

        final int finalOsebaId = osebaId;
        final int finalVerzija = verzija;

        executor.execute(() -> {
            int vZapisa = finalVerzija;
            if (vZapisa <= 0) {
                try {
                    vZapisa = RosKasaSoapClient.getVerzijaOfRacglava(prefs.getServerUrl(), prefs.getToken(), racunId);
                } catch (Exception ignored) {}
            }

            try {
                Globals.getInstance().vpisiKronologijoDebugL1(prefs.getServerUrl(), prefs.getToken(), prefs.getMobileId(),
                        "Storno računa R:" + racunId,
                        Globals.getInstance().getTekocaOsebaId(), Globals.getInstance().getTocilnicaId());

                StornoResult result = RosKasaSoapClient.setStornoRacuna(
                        prefs.getServerUrl(),
                        prefs.getToken(),
                        racunId,
                        vZapisa,
                        finalOsebaId,
                        stornoRazlogId,
                        novoNarocilo,
                        urejamStorno
                );

                RacunTp fetchedStornoRacun = null;
                int stornoRacunId = 0;
                String stornoIdStr = result != null ? result.getData1() : null;
                String novIdStr = result != null ? result.getData2() : null;

                if (result != null && result.isSuccess() && stornoIdStr != null && !stornoIdStr.trim().isEmpty() && !stornoIdStr.equals("0")) {
                    try {
                        stornoRacunId = Integer.parseInt(stornoIdStr.trim());
                        if (stornoRacunId > 0) {
                            fetchedStornoRacun = RosKasaSoapClient.getRacun(prefs.getServerUrl(), prefs.getToken(), stornoRacunId);
                            if (fetchedStornoRacun != null) {
                                // Preveri manjkajoče nazive artiklov
                                if (fetchedStornoRacun.getRacPozic() != null) {
                                    for (PozicijaTp p : fetchedStornoRacun.getRacPozic()) {
                                        if (p != null && p.getNivo4Id() != null && p.getNivo4Id() > 0) {
                                            String lookupName = Globals.getInstance().findNazivByNivo4Id(p.getNivo4Id());
                                            if (lookupName != null && !lookupName.trim().isEmpty()) {
                                                p.setNaziv(lookupName.trim());
                                            }
                                        }
                                    }
                                }

                                // Zagotovi osvežena plačila na storno računu (če strežnik ob stornu še ni zapisal ali vrnil plačil)
                                boolean hasNonZeroPlacilo = false;
                                if (fetchedStornoRacun.getRacPlaci() != null && !fetchedStornoRacun.getRacPlaci().isEmpty()) {
                                    for (PlaciloTp pl : fetchedStornoRacun.getRacPlaci()) {
                                        if (pl != null && (
                                                (pl.getDelniZnesek() != null && pl.getDelniZnesek().compareTo(BigDecimal.ZERO) != 0) ||
                                                (pl.getZnesek() != null && pl.getZnesek().compareTo(BigDecimal.ZERO) != 0)
                                        )) {
                                            hasNonZeroPlacilo = true;
                                            break;
                                        }
                                    }
                                }
                                if (!hasNonZeroPlacilo && racun != null && racun.getRacPlaci() != null && !racun.getRacPlaci().isEmpty()) {
                                    List<PlaciloTp> stornoPlaci = new ArrayList<>();
                                    int pozId = -1;
                                    for (PlaciloTp origPl : racun.getRacPlaci()) {
                                        if (origPl == null || origPl.isRowDeleted()) continue;
                                        PlaciloTp stPl = origPl.deepCopy();
                                        stPl.setRacunId(stornoRacunId);
                                        stPl.setPozicijaId(pozId--);
                                        if (stPl.getDelniZnesek() != null && stPl.getDelniZnesek().compareTo(BigDecimal.ZERO) != 0) {
                                            stPl.setDelniZnesek(stPl.getDelniZnesek().negate());
                                        }
                                        if (stPl.getZnesek() != null && stPl.getZnesek().compareTo(BigDecimal.ZERO) != 0) {
                                            stPl.setZnesek(stPl.getZnesek().negate());
                                        }
                                        stornoPlaci.add(stPl);
                                    }
                                    fetchedStornoRacun.setRacPlaci(stornoPlaci);
                                } else if (!hasNonZeroPlacilo && fetchedStornoRacun.getRacPlaci() != null && !fetchedStornoRacun.getRacPlaci().isEmpty()) {
                                    BigDecimal stornoZnesek = fetchedStornoRacun.getZnesek() != null && fetchedStornoRacun.getZnesek().compareTo(BigDecimal.ZERO) != 0
                                            ? fetchedStornoRacun.getZnesek()
                                            : (item != null && item.getZnesek() != null ? item.getZnesek().negate() : BigDecimal.ZERO);
                                    if (stornoZnesek.compareTo(BigDecimal.ZERO) != 0 && fetchedStornoRacun.getRacPlaci().size() == 1) {
                                        fetchedStornoRacun.getRacPlaci().get(0).setDelniZnesek(stornoZnesek);
                                    }
                                }

                                // 2.1. Ob izpisu storno računa storniran račun dobi STATUS=2 in STKOPIJ=1 na strežniku (Delphi uPrintData.pas:856-871)
                                try {
                                    fetchedStornoRacun.setStatus(2);
                                    fetchedStornoRacun.setStKopij(1);
                                    int mobileId = 0;
                                    try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                                    RosKasaSoapClient.setRacun(prefs.getServerUrl(), prefs.getToken(), mobileId, fetchedStornoRacun);
                                    Globals.getInstance().vpisiKronologijoDebugL1(prefs.getServerUrl(), prefs.getToken(), prefs.getMobileId(),
                                            "UpdateInvoiceStatus R:" + stornoRacunId + " na status 2 STKOPIJ:1",
                                            Globals.getInstance().getTekocaOsebaId(), Globals.getInstance().getTocilnicaId());
                                } catch (Exception exStatus) {
                                    Log.e("RacuniFragment", "Napaka pri posodobitvi statusa storno računa #" + stornoRacunId + ": " + exStatus.getMessage(), exStatus);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e("RacuniFragment", "Napaka pri obdelavi storno ID: " + stornoIdStr, e);
                    }
                }

                final RacunTp finalStornoRacun = fetchedStornoRacun;
                final int finalStornoRacunId = stornoRacunId;

                mainHandler.post(() -> {
                    if (binding != null) {
                        binding.pbLoading.setVisibility(View.GONE);
                    }
                    if (getContext() == null) return;

                    if (result != null && result.isSuccess()) {
                        // 1. Samodejno tiskanje storno računa (osvežen s pridobljenimi fiskalnimi podatki)
                        if (finalStornoRacun != null) {
                            BluetoothPrintHelper.printReceiptComplete(requireContext(), finalStornoRacun, new BluetoothPrintHelper.OnPrintListener() {
                                @Override
                                public void onStart() {
                                    // [DEBUG-TOAST] Toast.makeText(requireContext(), "Tiskanje storno računa #" + finalStornoRacunId + "...", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onSuccess(String message) {
                                    if (getContext() != null) {
                                        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Storno račun #" + finalStornoRacunId + " natisnjen.", Toast.LENGTH_SHORT).show();
                                    }
                                }

                                @Override
                                public void onError(String errorMessage) {
                                    if (getContext() != null) {
                                        new AlertDialog.Builder(requireContext())
                                                .setTitle("Napaka pri tiskanju storno računa")
                                                .setMessage("Storno račun #" + finalStornoRacunId + " je bil uspešno kreiran na strežniku, vendar tiskanje ni uspelo:\n\n" + errorMessage + "\n\nPreverite tiskalnik in kliknite 'Ponovi tisk'.")
                                                .setPositiveButton("Ponovi tisk", (dRetry, wRetry) -> {
                                                    if (finalStornoRacun != null) {
                                                        BluetoothPrintHelper.printReceiptComplete(requireContext(), finalStornoRacun, this);
                                                    }
                                                })
                                                .setNegativeButton("Zapri", null)
                                                .show();
                                    }
                                }
                            });
                        }

                        // 2. Osveži seznam računov (račun ima na strežniku že status 2)
                        loadRacuniFromApi();

                        // 3. Obvesti uporabnika in opcijsko preusmeri na nov račun
                        if (novIdStr != null && !novIdStr.trim().isEmpty() && !novIdStr.equals("0")) {
                            try {
                                int novRacunId = Integer.parseInt(novIdStr.trim());
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("Storno uspešen")
                                        .setMessage("Račun #" + racunId + " je bil uspešno storniran (storno račun #" + stornoIdStr + ").\n\nUstvarjen je bil nov račun #" + novRacunId + ".\nAli ga želite odpreti za urejanje?")
                                        .setPositiveButton("Da", (d, w) -> {
                                            prefs.setActiveRacunId(novRacunId);
                                            prefs.setActiveMarker(item.getMarker());
                                            Globals.getInstance().setCurrentRacun(null);
                                            if (getActivity() instanceof MainActivity) {
                                                ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
                                            }
                                        })
                                        .setNegativeButton("Ne", null)
                                        .show();
                                return;
                            } catch (Exception ignored) {}
                        }

                        new AlertDialog.Builder(requireContext())
                                .setTitle("Storno uspešen")
                                .setMessage("Račun #" + racunId + " je bil uspešno storniran (storno račun #" + stornoIdStr + ").")
                                .setPositiveButton("V redu", null)
                                .show();

                    } else {
                        String errMsg = (result != null && result.getFault() != null && !result.getFault().isEmpty())
                                ? result.getFault() : "Neznana napaka pri klicu setStornoRacuna.";
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Napaka pri stornu")
                                .setMessage(errMsg)
                                .setPositiveButton("Zapri", null)
                                .show();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (binding != null) {
                        binding.pbLoading.setVisibility(View.GONE);
                    }
                    if (getContext() != null) {
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Napaka pri stornu")
                                .setMessage("Prišlo je do napake: " + e.getMessage())
                                .setPositiveButton("Zapri", null)
                                .show();
                    }
                });
            }
        });
    }



    private void izvediPrintInkaso() {
        Globals g = Globals.getInstance();
        if (!g.isDovoljeno(PraviceConsts.SIzvedeKompletenFinancniPregled) && !g.isDovoljeno(PraviceConsts.SIzvedeFinancniPregled)) {
            Toast.makeText(requireContext(), "Nimate pravice za izpis inkasa!", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                int tocilnicaId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0) ? g.getTocilnicaId() : 0;
                int osebaId = g.isDovoljeno(PraviceConsts.SIzvedeKompletenFinancniPregled) ? 0 : (g.getTekocaOsebaId() > 0 ? g.getTekocaOsebaId() : 1);

                si.ros.RosKasa.models.SoapReportResult res = RosKasaSoapClient.financniPregled2(serverUrl, token, tocilnicaId, osebaId);
                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (res != null && res.getFault() != null && !res.getFault().isEmpty()) {
                        Toast.makeText(requireContext(), "Napaka pri izpisu inkasa: " + res.getFault(), Toast.LENGTH_LONG).show();
                    } else if (res != null && res.getPrintBytes() != null && res.getPrintBytes().length > 0) {
                        BluetoothPrintHelper.printReceiptBytes(requireContext(), res.getPrintBytes(), new BluetoothPrintHelper.OnPrintListener() {
                            @Override public void onStart() {
                                Toast.makeText(requireContext(), "Tiskanje inkasa...", Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onSuccess(String message) {
                                if (getContext() != null) Toast.makeText(requireContext(), "Inkaso uspešno natisnjen!", Toast.LENGTH_SHORT).show();
                                if (g.isNapitnine()) {
                                    izvediPrintNapitnineInkaso(serverUrl, token, tocilnicaId, osebaId);
                                }
                            }
                            @Override public void onError(String errorMessage) {
                                if (getContext() != null) Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show();
                            }
                        });
                        RosKasaSoapClient.vpisKronologijeAsync(serverUrl, token, prefs.getMobileId(), "PRINT INKASO za: " + tocilnicaId, g.getTekocaOsebaId(), tocilnicaId);
                    } else {
                        Toast.makeText(requireContext(), "Strežnik ni vrnil podatkov za izpis inkasa.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "Napaka: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void izvediPrintNapitnineInkaso(String serverUrl, String token, int tocilnicaId, int osebaId) {
        executor.execute(() -> {
            try {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_MONTH, -1);
                String odDat = new SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.US).format(cal.getTime());
                List<RosKasaSoapClient.NapitninaSkupajItem> items = RosKasaSoapClient.getNapitninaSkupaj(serverUrl, token, 2, tocilnicaId, osebaId, odDat);
                if (items == null || items.isEmpty()) return;

                Globals g = Globals.getInstance();
                int procKuh = g.getNapitnineProcentKuh();
                StringBuilder sb = new StringBuilder();
                sb.append("\n\nNAPITNINE NA DAN ").append(new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new java.util.Date())).append("\n\n");
                for (RosKasaSoapClient.NapitninaSkupajItem it : items) {
                    si.ros.RosKasa.models.OsebaTp os = g.najdiOseboById(it.kasiral);
                    String ime = (os != null && !os.getNaziv().isEmpty()) ? os.getNaziv() : ("Oseba #" + it.kasiral);
                    if (procKuh <= 0) {
                        sb.append(it.kasiral).append(" ").append(ime).append(" ")
                                .append(String.format(Locale.GERMANY, "%.2f EUR", it.napitnina)).append("\n");
                    } else {
                        BigDecimal kuh = it.napitnina.multiply(BigDecimal.valueOf(procKuh)).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                        BigDecimal osDel = it.napitnina.subtract(kuh);
                        sb.append(String.format(Locale.GERMANY, "%-25s: %.2f EUR\n", ime, osDel));
                        sb.append(String.format(Locale.GERMANY, "%-25s: %.2f EUR\n", "Delež za kuhinjo (" + procKuh + "%)", kuh));
                    }
                    sb.append("\n");
                }
                sb.append("\n\n\n");
                byte[] textBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                baos.write(new byte[]{0x1B, 0x40, 0x1C, 0x26, 0x1C, 0x43, (byte) 0xFF});
                baos.write(textBytes);
                baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
                final byte[] printBytes = baos.toByteArray();
                mainHandler.post(() -> {
                    if (getContext() != null) {
                        BluetoothPrintHelper.printReceiptBytes(requireContext(), printBytes, null);
                    }
                });
            } catch (Exception e) {
                Log.w("RacuniFragment", "Napaka pri izpisu napitnin: " + e.getMessage());
            }
        });
    }

    private void izvediObracun() {
        Globals g = Globals.getInstance();
        if (!g.isObracunDovoljen() || !g.isObracunAndroid() || !g.isDovoljeno(PraviceConsts.SLahkoPozeneObracun)) {
            Toast.makeText(requireContext(), "Obračun blagajne ni dovoljen v nastavitvah ali nimate pravice!", Toast.LENGTH_LONG).show();
            return;
        }

        AlertDialog.Builder confirm = new AlertDialog.Builder(requireContext());
        confirm.setTitle("Obračun blagajne");
        confirm.setMessage("Ali želite izvesti dnevni obračun blagajne?");
        confirm.setPositiveButton("Izvedi obračun", (d, w) -> {
            binding.pbLoading.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    int tocilnicaId = (g.getTocilnicaId() != null && g.getTocilnicaId() > 0) ? g.getTocilnicaId() : 0;
                    String repFormat = (g.getReportFormat() != null && !g.getReportFormat().trim().isEmpty())
                            ? g.getReportFormat().trim() : "ESC/POS";

                    si.ros.RosKasa.models.SoapReportResult res = RosKasaSoapClient.narediObracun(serverUrl, token, 1, tocilnicaId, 0, repFormat);
                    List<RacunSeznamItem> odprtiObNapaki = null;
                    if (res != null && res.getFault() != null && !res.getFault().isEmpty()) {
                        try {
                            List<RacunSeznamItem> allOpen = RosKasaSoapClient.getRacuniSeznam(serverUrl, token, prefs.getMobileId(), 1, "", 0, tocilnicaId);
                            if (allOpen != null && !allOpen.isEmpty()) {
                                odprtiObNapaki = allOpen;
                            }
                        } catch (Exception ignored) {}
                    }
                    final List<RacunSeznamItem> finalOdprti = odprtiObNapaki;
                    mainHandler.post(() -> {
                        binding.pbLoading.setVisibility(View.GONE);
                        if (res != null && res.getFault() != null && !res.getFault().isEmpty()) {
                            StringBuilder msg = new StringBuilder();
                            msg.append(res.getFault());
                            if (finalOdprti != null && !finalOdprti.isEmpty()) {
                                msg.append("\n\nOdprti računi (").append(finalOdprti.size()).append("):\n");
                                for (RacunSeznamItem r : finalOdprti) {
                                    String m = (r.getMarker() != null && !r.getMarker().isEmpty()) ? r.getMarker() : ("#" + r.getRacunId());
                                    BigDecimal z = r.getZnesek() != null ? r.getZnesek() : BigDecimal.ZERO;
                                    msg.append("• ").append(m)
                                       .append(" (R:").append(r.getRacunId()).append(") - ")
                                       .append(String.format(Locale.GERMAN, "%.2f €", z)).append("\n");
                                }
                            }
                            new AlertDialog.Builder(requireContext())
                                    .setTitle("Obračun NI izveden")
                                    .setMessage(msg.toString().trim())
                                    .setPositiveButton("V redu", null)
                                    .show();
                        } else if (!g.isObracunBrezIzpisa() && res != null && res.getPrintBytes() != null && res.getPrintBytes().length > 0) {
                            BluetoothPrintHelper.printReceiptBytes(requireContext(), res.getPrintBytes(), new BluetoothPrintHelper.OnPrintListener() {
                                @Override public void onStart() {
                                    Toast.makeText(requireContext(), "Tiskanje obračuna...", Toast.LENGTH_SHORT).show();
                                }
                                @Override public void onSuccess(String message) {
                                    if (getContext() != null) Toast.makeText(requireContext(), "Obračun zaključen in natisnjen!", Toast.LENGTH_SHORT).show();
                                }
                                @Override public void onError(String errorMessage) {
                                    if (getContext() != null) Toast.makeText(requireContext(), "Obračun končan, vendar tiskalnik ni povezan!", Toast.LENGTH_LONG).show();
                                }
                            });
                            RosKasaSoapClient.vpisKronologijeAsync(serverUrl, token, prefs.getMobileId(), "OBRAČUN BLAGAJNE KONČAN za: " + tocilnicaId, g.getTekocaOsebaId(), tocilnicaId);
                            loadRacuniFromApi();
                        } else {
                            RosKasaSoapClient.vpisKronologijeAsync(serverUrl, token, prefs.getMobileId(), "OBRAČUN BLAGAJNE KONČAN za: " + tocilnicaId, g.getTekocaOsebaId(), tocilnicaId);
                            Toast.makeText(requireContext(), g.isObracunBrezIzpisa() ? "Obračun zaključen (brez izpisa)." : "Obračun končan.", Toast.LENGTH_SHORT).show();
                            loadRacuniFromApi();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        binding.pbLoading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "Napaka: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        confirm.setNegativeButton("Prekliči", null);
        confirm.show();
    }

    private void izvediZdruziRacune() {
        ZdruziRacuneDialog.show(requireContext(), allItems, this::loadRacuniFromApi);
    }

    private void izvediSplitRacuna() {
        RacunSeznamItem selected = adapter.getSelectedItem();
        if (selected == null) {
            Toast.makeText(requireContext(), "Prosim izberite račun za delitev!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selected.getStatus() == null || selected.getStatus() != 1) {
            Toast.makeText(requireContext(), "Delitev je možna le na odprtih računih (status 1)!", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String serverUrl = prefs.getServerUrl();
                String token = prefs.getToken();
                RacunTp fullRacun = RosKasaSoapClient.getRacun(serverUrl, token, selected.getRacunId());
                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (fullRacun == null) {
                        Toast.makeText(requireContext(), "Napaka pri nalaganju računa za delitev!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SplitRacunDialog.show(requireContext(), fullRacun, this::loadRacuniFromApi);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "Napaka: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void izvediZamenjajMarker() {
        RacunSeznamItem selected = adapter.getSelectedItem();
        if (selected == null) {
            Toast.makeText(requireContext(), "Prosim izberite račun za zamenjavo markerja!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selected.getStatus() == null || selected.getStatus() != 1) {
            Toast.makeText(requireContext(), "Zamenjava markerja je možna le na odprtih računih (status 1)!", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Zamenjaj marker");
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        final String oldMarker = selected.getMarker() != null ? selected.getMarker() : "";
        input.setText(oldMarker);
        input.setSelectAllOnFocus(true);
        input.setPadding(32, 16, 32, 16);
        builder.setView(input);

        builder.setPositiveButton("Shrani", (d, w) -> {
            String raw = input.getText() != null ? input.getText().toString() : "";
            String clean = Globals.preveriMarker(raw);
            if (clean.isEmpty()) {
                Toast.makeText(requireContext(), "Marker ne sme biti prazen!", Toast.LENGTH_SHORT).show();
                return;
            }

            binding.pbLoading.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    RacunTp fullRacun = RosKasaSoapClient.getRacun(serverUrl, token, selected.getRacunId());
                    if (fullRacun != null) {
                        fullRacun.setMarker(clean);
                        int mobileId = 1;
                        try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}
                        RosKasaSoapClient.setRacun(serverUrl, token, mobileId, fullRacun);
                        Globals g = Globals.getInstance();
                        RosKasaSoapClient.vpisKronologijeAsync(serverUrl, token, prefs.getMobileId(),
                                "MARKER ZAMENJAVA R:" + fullRacun.getRacunId() + " iz: " + oldMarker + " v: " + clean,
                                g.getTekocaOsebaId(), fullRacun.getTocilnicaId());
                    }
                    mainHandler.post(() -> {
                        binding.pbLoading.setVisibility(View.GONE);
                        // [DEBUG-TOAST] Toast.makeText(requireContext(), "Marker posodobljen v: " + clean, Toast.LENGTH_SHORT).show();
                        loadRacuniFromApi();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        binding.pbLoading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "Napaka pri zamenjavi markerja: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        builder.setNegativeButton("Prekliči", null);
        builder.show();
    }

    @Override
    public void onItemDoubleClick(RacunSeznamItem item, int position) {
        if (item != null) {
            prefs.setActiveRacunId(item.getRacunId());
            prefs.setActiveMarker(item.getMarker());
            Globals.getInstance().setCurrentRacun(null);
            // [DEBUG-TOAST] Toast.makeText(requireContext(), "Nalaganje računa #" + item.getRacunId() + " za urejanje...", Toast.LENGTH_SHORT).show();
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToFragment(new NarocilaFragment());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
