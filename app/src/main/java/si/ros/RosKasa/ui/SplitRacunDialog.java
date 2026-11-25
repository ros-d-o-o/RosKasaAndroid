package si.ros.RosKasa.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.R;
import si.ros.RosKasa.models.PozicijaTp;
import si.ros.RosKasa.models.RacunTp;
import si.ros.RosKasa.soap.RosKasaSoapClient;

public class SplitRacunDialog {

    public interface OnSplitCompleteListener {
        void onSplitComplete();
    }

    public static void show(Context context, RacunTp originalRacun, OnSplitCompleteListener listener) {
        if (originalRacun == null || originalRacun.getRacPozic() == null || originalRacun.getRacPozic().isEmpty()) {
            Toast.makeText(context, "Račun nima postavk za delitev!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (originalRacun.getStatus() != 1) {
            Toast.makeText(context, "Delitev je možna le na odprtih računih (status 1)!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Vstopne zapore (skladno z Delphi FormSplit): brez plačil, DN_ID, AKCIJA_ID, BKARTA_ID
        if (originalRacun.getRacPlaci() != null) {
            for (si.ros.RosKasa.models.PlaciloTp pl : originalRacun.getRacPlaci()) {
                if (pl != null && !pl.isRowDeleted()) {
                    Toast.makeText(context, "Računa z vnesenimi plačili ali popusti ni mogoče deliti!", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        String dnId = originalRacun.getDnId() != null ? originalRacun.getDnId().trim() : "";
        if (!dnId.isEmpty() && !"0".equals(dnId)) {
            Toast.makeText(context, "Računa z delovnim nalogom ni mogoče deliti!", Toast.LENGTH_LONG).show();
            return;
        }
        if (originalRacun.getAkcijaId() != null && originalRacun.getAkcijaId() > 0) {
            Toast.makeText(context, "Računa z akcijo ni mogoče deliti!", Toast.LENGTH_LONG).show();
            return;
        }
        String bkartaId = originalRacun.getBkartaId() != null ? originalRacun.getBkartaId().trim() : "";
        if (!bkartaId.isEmpty() && !"0".equals(bkartaId)) {
            Toast.makeText(context, "Računa z bonitetno kartico ni mogoče deliti!", Toast.LENGTH_LONG).show();
            return;
        }

        List<PozicijaTp> sourceList = new ArrayList<>();
        for (PozicijaTp p : originalRacun.getRacPozic()) {
            if (p != null && !p.isRowDeleted() && p.getKolicina() > 0) {
                sourceList.add(p.deepCopy());
            }
        }
        if (sourceList.isEmpty()) {
            Toast.makeText(context, "Račun nima aktivnih postavk za delitev!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sourceList.size() == 1 && sourceList.get(0).getKolicina() <= 1.0) {
            Toast.makeText(context, "Računa z eno samo postavko (količina <= 1) ni mogoče deliti!", Toast.LENGTH_LONG).show();
            return;
        }

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_split_racun);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvOriginalLabel = dialog.findViewById(R.id.tvOriginalLabel);
        TextView tvOriginalZnesek = dialog.findViewById(R.id.tvOriginalZnesek);
        TextView tvNoviLabel = dialog.findViewById(R.id.tvNoviLabel);
        TextView tvNoviZnesek = dialog.findViewById(R.id.tvNoviZnesek);
        TextView tvEmptyOriginal = dialog.findViewById(R.id.tvEmptyOriginal);
        TextView tvEmptyNovi = dialog.findViewById(R.id.tvEmptyNovi);
        ProgressBar pbLoading = dialog.findViewById(R.id.pbLoading);
        RecyclerView rvOriginal = dialog.findViewById(R.id.rvOriginalPozicije);
        RecyclerView rvNovi = dialog.findViewById(R.id.rvNoviPozicije);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnPotrdiSplit = dialog.findViewById(R.id.btnPotrdiSplit);
        Button[] targetButtons = new Button[]{
                dialog.findViewById(R.id.btnSplitR1),
                dialog.findViewById(R.id.btnSplitR2),
                dialog.findViewById(R.id.btnSplitR3),
                dialog.findViewById(R.id.btnSplitR4),
                dialog.findViewById(R.id.btnSplitR5)
        };

        String origMarker = originalRacun.getMarker() != null && !originalRacun.getMarker().isEmpty()
                ? originalRacun.getMarker()
                : ("Račun #" + originalRacun.getRacunId());
        tvOriginalLabel.setText("Originalni račun (" + origMarker + " #" + originalRacun.getRacunId() + "):");

        // 5 ciljnih računov (R1..R5, skladno z Delphi FormSplit)
        final List<List<PozicijaTp>> targetLists = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            targetLists.add(new ArrayList<>());
        }
        final int[] activeTarget = {0};
        final List<PozicijaTp> displayedTargetList = new ArrayList<>();

        SplitPozicijeAdapter sourceAdapter = new SplitPozicijeAdapter(sourceList);
        SplitPozicijeAdapter targetAdapter = new SplitPozicijeAdapter(displayedTargetList);

        rvOriginal.setLayoutManager(new LinearLayoutManager(context));
        rvOriginal.setAdapter(sourceAdapter);

        rvNovi.setLayoutManager(new LinearLayoutManager(context));
        rvNovi.setAdapter(targetAdapter);

        Runnable updateSums = () -> {
            BigDecimal sumSource = BigDecimal.ZERO;
            for (PozicijaTp p : sourceList) {
                if (p.getZnesek() != null) sumSource = sumSource.add(p.getZnesek());
            }
            List<PozicijaTp> curTarget = targetLists.get(activeTarget[0]);
            displayedTargetList.clear();
            displayedTargetList.addAll(curTarget);
            targetAdapter.notifyDataSetChanged();

            BigDecimal sumTarget = BigDecimal.ZERO;
            for (PozicijaTp p : curTarget) {
                if (p.getZnesek() != null) sumTarget = sumTarget.add(p.getZnesek());
            }

            tvOriginalZnesek.setText(String.format(Locale.GERMAN, "%.2f €", sumSource));
            tvNoviLabel.setText("Novi račun R" + (activeTarget[0] + 1) + ":");
            tvNoviZnesek.setText(String.format(Locale.GERMAN, "%.2f €", sumTarget));

            tvEmptyOriginal.setVisibility(sourceList.isEmpty() ? View.VISIBLE : View.GONE);
            tvEmptyNovi.setVisibility(curTarget.isEmpty() ? View.VISIBLE : View.GONE);

            for (int i = 0; i < targetButtons.length; i++) {
                Button b = targetButtons[i];
                if (b == null) continue;
                int cnt = targetLists.get(i).size();
                b.setText(cnt > 0 ? ("R" + (i + 1) + " (" + cnt + ")") : ("R" + (i + 1)));
                int color = (i == activeTarget[0]) ? 0xFF1565C0 : (cnt > 0 ? 0xFF2E7D32 : 0xFF455A64);
                b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            }
        };

        for (int i = 0; i < targetButtons.length; i++) {
            final int idx = i;
            if (targetButtons[i] != null) {
                targetButtons[i].setOnClickListener(v -> {
                    activeTarget[0] = idx;
                    updateSums.run();
                });
            }
        }

        sourceAdapter.setOnItemClickListener(pos -> {
            if (pos < 0 || pos >= sourceList.size()) return;
            PozicijaTp sourcePoz = sourceList.get(pos);
            List<PozicijaTp> targetList = targetLists.get(activeTarget[0]);

            // Če je postavka del paketa (PAKET_DISTINCT > 0), premaknemo vse komponente tega paketa kot celoto
            Integer paketDist = sourcePoz.getPaketDistinct();
            if (paketDist != null && paketDist > 0) {
                List<PozicijaTp> toMove = new ArrayList<>();
                for (PozicijaTp sp : sourceList) {
                    if (sp.getPaketDistinct() != null && sp.getPaketDistinct().equals(paketDist)) {
                        toMove.add(sp);
                    }
                }
                for (PozicijaTp sp : toMove) {
                    targetList.add(sp.deepCopy());
                }
                sourceList.removeAll(toMove);
                sourceAdapter.notifyDataSetChanged();
                updateSums.run();
                return;
            }

            double kol = sourcePoz.getKolicina();
            boolean isDecimal = Math.abs(kol - Math.rint(kol)) > 1e-6;
            double moveKol = (isDecimal || kol <= 1.0) ? kol : 1.0;

            PozicijaTp targetPoz = null;
            for (PozicijaTp tp : targetList) {
                if (tp.getPozicijaId() == sourcePoz.getPozicijaId()) {
                    targetPoz = tp;
                    break;
                }
            }

            if (targetPoz == null) {
                targetPoz = sourcePoz.deepCopy();
                targetPoz.setKolicina(moveKol);
                targetPoz.recalculateZnesek();
                targetList.add(targetPoz);
            } else {
                targetPoz.setKolicina(targetPoz.getKolicina() + moveKol);
                targetPoz.recalculateZnesek();
            }

            double remainingKol = kol - moveKol;
            if (remainingKol <= 1e-6) {
                sourceList.remove(pos);
            } else {
                sourcePoz.setKolicina(remainingKol);
                sourcePoz.recalculateZnesek();
            }

            sourceAdapter.notifyDataSetChanged();
            updateSums.run();
        });

        targetAdapter.setOnItemClickListener(pos -> {
            List<PozicijaTp> targetList = targetLists.get(activeTarget[0]);
            if (pos < 0 || pos >= targetList.size()) return;
            PozicijaTp targetPoz = targetList.get(pos);

            // Paket (PAKET_DISTINCT > 0) vračamo nazaj kot celoto
            Integer paketDist = targetPoz.getPaketDistinct();
            if (paketDist != null && paketDist > 0) {
                List<PozicijaTp> toMove = new ArrayList<>();
                for (PozicijaTp tp : targetList) {
                    if (tp.getPaketDistinct() != null && tp.getPaketDistinct().equals(paketDist)) {
                        toMove.add(tp);
                    }
                }
                for (PozicijaTp tp : toMove) {
                    sourceList.add(tp.deepCopy());
                }
                targetList.removeAll(toMove);
                sourceAdapter.notifyDataSetChanged();
                updateSums.run();
                return;
            }

            double kol = targetPoz.getKolicina();
            boolean isDecimal = Math.abs(kol - Math.rint(kol)) > 1e-6;
            double moveKol = (isDecimal || kol <= 1.0) ? kol : 1.0;

            PozicijaTp sourcePoz = null;
            for (PozicijaTp sp : sourceList) {
                if (sp.getPozicijaId() == targetPoz.getPozicijaId()) {
                    sourcePoz = sp;
                    break;
                }
            }

            if (sourcePoz == null) {
                sourcePoz = targetPoz.deepCopy();
                sourcePoz.setKolicina(moveKol);
                sourcePoz.recalculateZnesek();
                sourceList.add(sourcePoz);
            } else {
                sourcePoz.setKolicina(sourcePoz.getKolicina() + moveKol);
                sourcePoz.recalculateZnesek();
            }

            double remainingKol = kol - moveKol;
            if (remainingKol <= 1e-6) {
                targetList.remove(pos);
            } else {
                targetPoz.setKolicina(remainingKol);
                targetPoz.recalculateZnesek();
            }

            sourceAdapter.notifyDataSetChanged();
            updateSums.run();
        });

        updateSums.run();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnPotrdiSplit.setOnClickListener(v -> {
            int nonEmptyTargets = 0;
            for (List<PozicijaTp> tl : targetLists) {
                if (!tl.isEmpty()) nonEmptyTargets++;
            }
            if (nonEmptyTargets == 0) {
                Toast.makeText(context, "Na vsaj en novi račun (R1..R5) morate prenesti postavko!", Toast.LENGTH_SHORT).show();
                return;
            }

            final int finalNonEmptyTargets = nonEmptyTargets;
            AlertDialog.Builder confirm = new AlertDialog.Builder(context);
            confirm.setTitle("Potrdi delitev računa");
            confirm.setMessage("Ali potrjujete delitev računa (ustvarjenih bo " + finalNonEmptyTargets + " novih računov)?");
            confirm.setPositiveButton("Potrdi", (d, w) -> {
                pbLoading.setVisibility(View.VISIBLE);
                btnPotrdiSplit.setEnabled(false);

                ExecutorService executor = Executors.newSingleThreadExecutor();
                Handler mainHandler = new Handler(Looper.getMainLooper());
                AppPreferences prefs = new AppPreferences(context);

                executor.execute(() -> {
                    try {
                        String serverUrl = prefs.getServerUrl();
                        String token = prefs.getToken();
                        int mobileId = 1;
                        try { mobileId = Integer.parseInt(prefs.getMobileId()); } catch (Exception ignored) {}

                        // 1. Posodobi originalni račun (brisanje ali zmanjšanje prenesenih postavk)
                        RacunTp origUpdated = originalRacun.deepCopy();
                        List<PozicijaTp> origPositions = new ArrayList<>();

                        for (PozicijaTp origP : originalRacun.getRacPozic()) {
                            PozicijaTp remaining = null;
                            for (PozicijaTp sp : sourceList) {
                                if (sp.getPozicijaId() == origP.getPozicijaId()) {
                                    remaining = sp;
                                    break;
                                }
                            }
                            if (remaining != null && remaining.getKolicina() > 0) {
                                PozicijaTp copy = origP.deepCopy();
                                copy.setKolicina(remaining.getKolicina());
                                copy.recalculateZnesek();
                                copy.setStatus(BigDecimal.valueOf(remaining.getKolicina()));
                                copy.setNeNarocaj(true);
                                copy.setStatusPoz(9);
                                copy.setOriginalObject(null);
                                origPositions.add(copy);
                            } else {
                                // Postavka je bila v celoti prenesena - brišemo jo iz originalnega računa
                                if (origP.getPozicijaId() > 0) {
                                    PozicijaTp copy = origP.deepCopy();
                                    copy.setRowDeleted(true);
                                    copy.setNeNarocaj(true);
                                    copy.setStatusPoz(9);
                                    copy.setOriginalObject(null);
                                    origPositions.add(copy);
                                }
                            }
                        }
                        origUpdated.setRacPozic(origPositions);

                        BigDecimal sumOrig = BigDecimal.ZERO;
                        for (PozicijaTp p : origPositions) {
                            if (!p.isRowDeleted() && p.getZnesek() != null) {
                                sumOrig = sumOrig.add(p.getZnesek());
                            }
                        }
                        origUpdated.setZnesek(sumOrig);
                        String curKoda = origUpdated.getfPrintKoda() != null ? origUpdated.getfPrintKoda() : "";
                        if (!curKoda.contains("SPLIT")) {
                            origUpdated.setfPrintKoda(curKoda.trim() + " SPLIT ");
                        }

                        RosKasaSoapClient.setRacun(serverUrl, token, mobileId, origUpdated);

                        // 2. Ustvari nove deljene račune (za vsak neprazen R1..R5)
                        String baseMarker = originalRacun.getMarker() != null && !originalRacun.getMarker().isEmpty()
                                ? originalRacun.getMarker()
                                : ("R" + originalRacun.getRacunId());
                        StringBuilder createdMarkers = new StringBuilder();
                        for (int idx = 0; idx < targetLists.size(); idx++) {
                            List<PozicijaTp> tList = targetLists.get(idx);
                            if (tList.isEmpty()) continue;

                            RacunTp noviRacun = new RacunTp();
                            noviRacun.setRacunId(Globals.getNextNegativeRacunId());
                            noviRacun.setMarker(baseMarker + "_" + (idx + 1));
                            noviRacun.setStatus(1);
                            noviRacun.setTocilnicaId(originalRacun.getTocilnicaId() != null && originalRacun.getTocilnicaId() > 0 ? originalRacun.getTocilnicaId() : 512200);
                            noviRacun.setfPosId(originalRacun.getfPosId() != null && originalRacun.getfPosId() > 0 ? originalRacun.getfPosId() : 500);
                            noviRacun.setfPoslovniProstorId(originalRacun.getfPoslovniProstorId() != null && originalRacun.getfPoslovniProstorId() > 0 ? originalRacun.getfPoslovniProstorId() : 5000);
                            noviRacun.setKasiral(Globals.getInstance().getTekocaOsebaId() > 0 ? Globals.getInstance().getTekocaOsebaId() : (originalRacun.getKasiral() != null ? originalRacun.getKasiral() : 9999));
                            noviRacun.setTipRacuna(1);
                            noviRacun.setStPogrinjkov(1);
                            noviRacun.setStKopij(0);
                            noviRacun.setfPrintKoda(baseMarker + " " + originalRacun.getRacunId() + " ");
                            noviRacun.setOriginalObject(null);

                            List<PozicijaTp> noviPositions = new ArrayList<>();
                            int pId = -1;
                            BigDecimal sumNovi = BigDecimal.ZERO;
                            for (PozicijaTp tp : tList) {
                                PozicijaTp np = tp.deepCopy();
                                np.setPozicijaId(pId--);
                                np.setRacunId(noviRacun.getRacunId());
                                np.setVerzijaZapisa(0);
                                np.setOriginalObject(null);
                                np.setRowDeleted(false);
                                np.setNeNarocaj(true);
                                if (originalRacun.getRacunId() > 0 && np.getStatus() != null && np.getStatus().compareTo(BigDecimal.ZERO) > 0) {
                                    np.setStatus(BigDecimal.valueOf(np.getKolicina()));
                                } else {
                                    np.setStatus(BigDecimal.ZERO);
                                }
                                np.setStatusPoz(0);
                                np.setNarociloPoslano(0);
                                noviPositions.add(np);
                                if (np.getZnesek() != null) sumNovi = sumNovi.add(np.getZnesek());
                            }
                            noviRacun.setRacPozic(noviPositions);
                            noviRacun.setZnesek(sumNovi);

                            RosKasaSoapClient.setRacun(serverUrl, token, mobileId, noviRacun);
                            if (createdMarkers.length() > 0) createdMarkers.append(", ");
                            createdMarkers.append(noviRacun.getMarker());
                        }

                        Globals.getInstance().vpisiKronologijoDebugL0(serverUrl, token, String.valueOf(mobileId),
                                "SPLIT iz original R:" + originalRacun.getRacunId() + " v nove R: " + createdMarkers,
                                Globals.getInstance().getTekocaOsebaId(), Globals.getInstance().getTocilnicaId());

                        mainHandler.post(() -> {
                            pbLoading.setVisibility(View.GONE);
                            Toast.makeText(context, "Račun je bil uspešno razdeljen (" + createdMarkers + ")!", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            if (listener != null) listener.onSplitComplete();
                        });
                    } catch (Exception e) {
                        mainHandler.post(() -> {
                            pbLoading.setVisibility(View.GONE);
                            btnPotrdiSplit.setEnabled(true);
                            Toast.makeText(context, "Napaka pri delitvi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            });
            confirm.setNegativeButton("Prekliči", null);
            confirm.show();
        });

        dialog.show();
    }

    private static class SplitPozicijeAdapter extends RecyclerView.Adapter<SplitPozicijeAdapter.ViewHolder> {
        private final List<PozicijaTp> items;
        private OnItemClickListener listener;

        public interface OnItemClickListener {
            void onItemClick(int position);
        }

        public SplitPozicijeAdapter(List<PozicijaTp> items) {
            this.items = items;
        }

        public void setOnItemClickListener(OnItemClickListener listener) {
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_split_pozicija, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PozicijaTp item = items.get(position);
            holder.tvNaziv.setText(item.getNaziv() != null && !item.getNaziv().isEmpty() ? item.getNaziv() : ("Artikel " + item.getNivo4Id()));
            String kolStr = (Math.abs(item.getKolicina() - Math.rint(item.getKolicina())) > 1e-6)
                    ? String.format(Locale.GERMAN, "%.2fx", item.getKolicina())
                    : String.format(Locale.GERMAN, "%.0fx", item.getKolicina());
            holder.tvKolicina.setText(kolStr);
            String znStr = item.getZnesek() != null ? String.format(Locale.GERMAN, "%.2f €", item.getZnesek()) : "0,00 €";
            holder.tvZnesek.setText(znStr);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(position);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvNaziv;
            final TextView tvKolicina;
            final TextView tvZnesek;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvNaziv = itemView.findViewById(R.id.tvNaziv);
                tvKolicina = itemView.findViewById(R.id.tvKolicina);
                tvZnesek = itemView.findViewById(R.id.tvZnesek);
            }
        }
    }
}
