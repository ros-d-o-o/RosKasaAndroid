package si.ros.RosKasa.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Dialog za vnos napitnine (NAPITNINAROS), skladen z Delphi FormNapitnine.pas.
 * Sinhronizirano preračunava % napitnine, znesek napitnine (€) in končni znesek.
 */
public class NapitnineDialog {

    public interface OnNapitninaListener {
        void onNapitninaConfirmed(BigDecimal tipAmount, BigDecimal finalAmount);
        void onCancelled();
    }

    public static void show(@NonNull Context context, BigDecimal startingAmount, OnNapitninaListener listener) {
        final BigDecimal baseAmt = (startingAmount != null && startingAmount.compareTo(BigDecimal.ZERO) > 0)
                ? startingAmount.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView tvStart = new TextView(context);
        tvStart.setText(String.format(Locale.GERMANY, "Osnovni znesek računa: %.2f €", baseAmt));
        tvStart.setTextSize(16);
        root.addView(tvStart);

        // Hitri procenti (0%, 5%, 10%, 15%)
        LinearLayout quickRow = new LinearLayout(context);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setPadding(0, pad / 2, 0, pad / 2);

        TextView tvPercLabel = new TextView(context);
        tvPercLabel.setText("Napitnina (%):");
        EditText etPerc = new EditText(context);
        etPerc.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPerc.setText("0");

        TextView tvTipLabel = new TextView(context);
        tvTipLabel.setText("Napitnina (€):");
        EditText etTip = new EditText(context);
        etTip.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etTip.setText("0.00");

        TextView tvFinalLabel = new TextView(context);
        tvFinalLabel.setText("Končni znesek (€):");
        EditText etFinal = new EditText(context);
        etFinal.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etFinal.setText(String.format(Locale.US, "%.2f", baseAmt));

        final boolean[] updating = new boolean[]{false};

        int[] quickPercs = new int[]{0, 5, 10, 15};
        for (int p : quickPercs) {
            Button b = new Button(context);
            b.setText(p + "%");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> etPerc.setText(String.valueOf(p)));
            quickRow.addView(b);
        }

        etPerc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                updating[0] = true;
                try {
                    BigDecimal perc = parseBD(s.toString());
                    if (perc.compareTo(BigDecimal.ZERO) < 0) perc = BigDecimal.ZERO;
                    BigDecimal tip = baseAmt.multiply(perc).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    etTip.setText(String.format(Locale.US, "%.2f", tip));
                    etFinal.setText(String.format(Locale.US, "%.2f", baseAmt.add(tip)));
                } catch (Exception ignored) {}
                updating[0] = false;
            }
        });

        etTip.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                updating[0] = true;
                try {
                    BigDecimal tip = parseBD(s.toString());
                    if (tip.compareTo(BigDecimal.ZERO) < 0) tip = BigDecimal.ZERO;
                    BigDecimal perc = baseAmt.compareTo(BigDecimal.ZERO) > 0
                            ? tip.multiply(BigDecimal.valueOf(100)).divide(baseAmt, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    etPerc.setText(String.format(Locale.US, "%.2f", perc));
                    etFinal.setText(String.format(Locale.US, "%.2f", baseAmt.add(tip)));
                } catch (Exception ignored) {}
                updating[0] = false;
            }
        });

        etFinal.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                updating[0] = true;
                try {
                    BigDecimal fin = parseBD(s.toString());
                    BigDecimal tip = fin.subtract(baseAmt);
                    if (tip.compareTo(BigDecimal.ZERO) < 0) tip = BigDecimal.ZERO;
                    BigDecimal perc = baseAmt.compareTo(BigDecimal.ZERO) > 0
                            ? tip.multiply(BigDecimal.valueOf(100)).divide(baseAmt, 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    etTip.setText(String.format(Locale.US, "%.2f", tip));
                    etPerc.setText(String.format(Locale.US, "%.2f", perc));
                } catch (Exception ignored) {}
                updating[0] = false;
            }
        });

        root.addView(quickRow);
        root.addView(tvPercLabel);
        root.addView(etPerc);
        root.addView(tvTipLabel);
        root.addView(etTip);
        root.addView(tvFinalLabel);
        root.addView(etFinal);

        new AlertDialog.Builder(context)
                .setTitle("Napitnina")
                .setView(root)
                .setPositiveButton("Potrdi", (d, w) -> {
                    BigDecimal tip = parseBD(etTip.getText().toString()).setScale(2, RoundingMode.HALF_UP);
                    if (tip.compareTo(BigDecimal.ZERO) < 0) tip = BigDecimal.ZERO;
                    if (tip.compareTo(new BigDecimal("1000")) > 0) {
                        Toast.makeText(context, "Napitnina ne more presegati 1000 €!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BigDecimal fin = baseAmt.add(tip);
                    if (listener != null) listener.onNapitninaConfirmed(tip, fin);
                })
                .setNegativeButton("Prekliči", (d, w) -> {
                    if (listener != null) listener.onCancelled();
                })
                .show();
    }

    private static BigDecimal parseBD(String s) {
        if (s == null) return BigDecimal.ZERO;
        String clean = s.replace(",", ".").replaceAll("[^0-9.]", "").trim();
        if (clean.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
