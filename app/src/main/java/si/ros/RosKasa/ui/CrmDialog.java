package si.ros.RosKasa.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import si.ros.RosKasa.AppPreferences;
import si.ros.RosKasa.Globals;
import si.ros.RosKasa.MainActivity;
import si.ros.RosKasa.soap.RosKasaSoapClient;

/**
 * Dialog za iskanje in dodelitev CRM gosta / kartice zvestobe (skladno z Delphi FormCrm.pas).
 * Podpira skeniranje kartice (odstraniVodilneNicle + 30-znak. trojni sken), iskanje po imenu (searchGost),
 * pridobivanje stanja in popusta (crmInfo) ter GDPR beleženje vpogleda (gdprLog).
 */
public class CrmDialog {

    public interface OnCrmSelectedListener {
        void onCrmSelected(String crmId, String gostNaziv, BigDecimal popustProc);
    }

    /**
     * Normalizira številko CRM kartice skladno z Delphi FormCrm.OdstraniVodilneNicle:
     * pri 30-znakovnem trojnem skenu vzame zadnjih 10 znakov, nato odstrani vodilne ničle.
     */
    public static String odstraniVodilneNicle(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.length() == 30) {
            s = s.substring(20);
        }
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    public static void show(Context context, int racunId, String currentCrmId, OnCrmSelectedListener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E272C);
        root.setPadding(32, 28, 32, 28);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("CRM GOST / KARTICA ZVESTOBE");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 0, 0, 16);
        root.addView(tvTitle);

        EditText etInput = new EditText(context);
        etInput.setHint("Št. kartice ali priimek/ime gosta...");
        etInput.setTextColor(Color.WHITE);
        etInput.setHintTextColor(0xFF78909C);
        etInput.setBackgroundColor(0xFF263238);
        etInput.setPadding(20, 18, 20, 18);
        etInput.setSingleLine(true);
        if (currentCrmId != null && !currentCrmId.trim().isEmpty()) {
            etInput.setText(currentCrmId.trim());
        }
        root.addView(etInput);

        LinearLayout btnSearchRow = new LinearLayout(context);
        btnSearchRow.setOrientation(LinearLayout.HORIZONTAL);
        btnSearchRow.setPadding(0, 14, 0, 10);

        Button btnPoKartici = new Button(context);
        btnPoKartici.setText("Preveri kartico");
        btnPoKartici.setTextColor(Color.WHITE);
        btnPoKartici.setBackgroundColor(0xFF00897B);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMarginEnd(8);
        btnPoKartici.setLayoutParams(lp1);
        btnSearchRow.addView(btnPoKartici);

        Button btnIsciGosta = new Button(context);
        btnIsciGosta.setText("Išči gosta");
        btnIsciGosta.setTextColor(Color.WHITE);
        btnIsciGosta.setBackgroundColor(0xFF1565C0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMarginStart(8);
        btnIsciGosta.setLayoutParams(lp2);
        btnSearchRow.addView(btnIsciGosta);
        root.addView(btnSearchRow);

        ProgressBar pb = new ProgressBar(context);
        pb.setVisibility(View.GONE);
        root.addView(pb);

        ScrollView scroll = new ScrollView(context);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360);
        scrollLp.topMargin = 10;
        scrollLp.bottomMargin = 14;
        scroll.setLayoutParams(scrollLp);
        scroll.setBackgroundColor(0xFF263238);

        LinearLayout resultsBox = new LinearLayout(context);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setPadding(16, 16, 16, 16);

        TextView tvInfo = new TextView(context);
        tvInfo.setText("Prislonite CRM kartico, vnesite številko kartice ali poiščite gosta po imenu.");
        tvInfo.setTextColor(0xFFB0BEC5);
        tvInfo.setTextSize(14);
        resultsBox.addView(tvInfo);

        scroll.addView(resultsBox);
        root.addView(scroll);

        LinearLayout footerRow = new LinearLayout(context);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancel = new Button(context);
        btnCancel.setText("Prekliči");
        btnCancel.setTextColor(Color.WHITE);
        btnCancel.setBackgroundColor(0xFF455A64);
        LinearLayout.LayoutParams flp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp1.setMarginEnd(8);
        btnCancel.setLayoutParams(flp1);
        footerRow.addView(btnCancel);

        Button btnPotrdi = new Button(context);
        btnPotrdi.setText("Potrdi CRM");
        btnPotrdi.setTextColor(Color.WHITE);
        btnPotrdi.setBackgroundColor(0xFF2E7D32);
        btnPotrdi.setEnabled(false);
        LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp2.setMarginStart(8);
        btnPotrdi.setLayoutParams(flp2);
        footerRow.addView(btnPotrdi);
        root.addView(footerRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final String[] selectedCrmId = {""};
        final String[] selectedNaziv = {""};
        final BigDecimal[] selectedPopust = {BigDecimal.ZERO};

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        AppPreferences prefs = new AppPreferences(context);

        java.util.function.BiConsumer<String, Long> loadCrmInfo = (stKarticeRaw, gostId) -> {
            String cleanCard = odstraniVodilneNicle(stKarticeRaw);
            pb.setVisibility(View.VISIBLE);
            btnPotrdi.setEnabled(false);
            executor.execute(() -> {
                try {
                    String serverUrl = prefs.getServerUrl();
                    String token = prefs.getToken();
                    RosKasaSoapClient.CrmInfoResult info = RosKasaSoapClient.crmInfo(
                            serverUrl, token, (gostId != null && gostId > 0) ? gostId : null, cleanCard);

                    if (gostId != null && gostId > 0) {
                        RosKasaSoapClient.gdprLog(serverUrl, token, "gost", gostId,
                                "KasaMobile", "VPOGLED PODATKOV CRM " + racunId);
                    }

                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        resultsBox.removeAllViews();
                        if (info == null || (info.fault != null && !info.fault.trim().isEmpty())) {
                            String err = (info != null && info.fault != null && !info.fault.isEmpty())
                                    ? info.fault : "CRM podatki niso bili najdeni.";
                            TextView tvErr = new TextView(context);
                            tvErr.setText("Napaka: " + err);
                            tvErr.setTextColor(0xFFFF8A80);
                            tvErr.setTextSize(14);
                            resultsBox.addView(tvErr);
                            return;
                        }
                        String polnoIme = (info.ime + " " + info.priimek).trim();
                        selectedCrmId[0] = !cleanCard.isEmpty() ? cleanCard : String.valueOf(gostId);
                        selectedNaziv[0] = polnoIme;
                        selectedPopust[0] = info.popustProc != null ? info.popustProc : BigDecimal.ZERO;

                        TextView tvDetails = new TextView(context);
                        StringBuilder sb = new StringBuilder();
                        sb.append("Gost: ").append(polnoIme.isEmpty() ? "-" : polnoIme).append("\n");
                        if (!selectedCrmId[0].isEmpty()) {
                            sb.append("CRM ID / Kartica: ").append(selectedCrmId[0]).append("\n");
                        }
                        if (info.opis != null && !info.opis.isEmpty()) {
                            sb.append("Opis: ").append(info.opis).append("\n");
                        }
                        sb.append(String.format(Locale.GERMAN, "Stanje točk: %.2f\n", info.stanje != null ? info.stanje : BigDecimal.ZERO));
                        sb.append(String.format(Locale.GERMAN, "CRM popust: %.2f %%", selectedPopust[0]));
                        tvDetails.setText(sb.toString());
                        tvDetails.setTextColor(Color.WHITE);
                        tvDetails.setTextSize(15);
                        resultsBox.addView(tvDetails);
                        btnPotrdi.setEnabled(true);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        Toast.makeText(context, "Napaka CRM: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        };

        btnPoKartici.setOnClickListener(v -> {
            String raw = etInput.getText() != null ? etInput.getText().toString().trim() : "";
            if (raw.isEmpty()) {
                Toast.makeText(context, "Vnesite številko kartice!", Toast.LENGTH_SHORT).show();
                return;
            }
            loadCrmInfo.accept(raw, 0L);
        });

        btnIsciGosta.setOnClickListener(v -> {
            String q = etInput.getText() != null ? etInput.getText().toString().trim() : "";
            if (q.length() < 2) {
                Toast.makeText(context, "Vnesite vsaj 2 znaka za iskanje gosta!", Toast.LENGTH_SHORT).show();
                return;
            }
            pb.setVisibility(View.VISIBLE);
            btnPotrdi.setEnabled(false);
            executor.execute(() -> {
                try {
                    List<RosKasaSoapClient.CrmGostItem> gosti = RosKasaSoapClient.searchGost(
                            prefs.getServerUrl(), prefs.getToken(), q);
                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        resultsBox.removeAllViews();
                        if (gosti == null || gosti.isEmpty()) {
                            TextView tvNone = new TextView(context);
                            tvNone.setText("Ni najdenih gostov za: \"" + q + "\"");
                            tvNone.setTextColor(0xFFB0BEC5);
                            resultsBox.addView(tvNone);
                            return;
                        }
                        for (RosKasaSoapClient.CrmGostItem g : gosti) {
                            Button rowBtn = new Button(context);
                            rowBtn.setText(g.naziv + (!g.naslov.isEmpty() ? (" (" + g.naslov + ")") : ""));
                            rowBtn.setTextColor(Color.WHITE);
                            rowBtn.setBackgroundColor(0xFF37474F);
                            rowBtn.setAllCaps(false);
                            rowBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            rlp.bottomMargin = 8;
                            rowBtn.setLayoutParams(rlp);
                            rowBtn.setOnClickListener(rv -> loadCrmInfo.accept("", g.gostId));
                            resultsBox.addView(rowBtn);
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        Toast.makeText(context, "Napaka pri iskanju gosta: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        if (context instanceof MainActivity) {
            MainActivity mainAct = (MainActivity) context;
            mainAct.setOnNfcTagReadListener(cardInfo -> {
                if (cardInfo != null && dialog.isShowing()) {
                    mainHandler.post(() -> {
                        String code = (cardInfo.decimalId != null && !cardInfo.decimalId.isEmpty())
                                ? cardInfo.decimalId
                                : (cardInfo.hexId != null ? cardInfo.hexId : "");
                        if (!code.isEmpty()) {
                            etInput.setText(code);
                            loadCrmInfo.accept(code, 0L);
                        }
                    });
                }
            });
            dialog.setOnDismissListener(d -> mainAct.setOnNfcTagReadListener(null));
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnPotrdi.setOnClickListener(v -> {
            if (!selectedCrmId[0].isEmpty()) {
                dialog.dismiss();
                if (listener != null) {
                    listener.onCrmSelected(selectedCrmId[0], selectedNaziv[0], selectedPopust[0]);
                }
            }
        });

        dialog.show();
    }
}
