package si.ros.RosKasa.ui;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.models.CenikVrVrTp;

/**
 * Izbirni dialog za Mix & Match pakete (pompaket = 2) iz CENIKVRVR,
 * skladen z Delphi RosKasaLight16.
 */
public class PaketMixMatchDialog {

    public interface OnMixMatchSelectedListener {
        void onComponentsSelected(List<CenikVrVrTp> selectedComponents);
        void onCancelled();
    }

    public static void show(@NonNull Context context,
                            String paketNaziv,
                            BigDecimal paketCena,
                            @NonNull List<CenikVrVrTp> availableComponents,
                            OnMixMatchSelectedListener listener) {
        if (availableComponents.isEmpty()) {
            Toast.makeText(context, "Paket nima definiranih sestavin v CENIKVRVR!", Toast.LENGTH_SHORT).show();
            if (listener != null) listener.onCancelled();
            return;
        }

        Globals g = Globals.getInstance();
        String[] labels = new String[availableComponents.size()];
        boolean[] checked = new boolean[availableComponents.size()];

        for (int i = 0; i < availableComponents.size(); i++) {
            CenikVrVrTp comp = availableComponents.get(i);
            String naziv = g.findNazivByNivo4Id(comp.getNivo4Id());
            if (naziv == null || naziv.trim().isEmpty()) {
                naziv = "Artikel #" + comp.getNivo4Id();
            }
            String kolStr = (Math.abs(comp.getKolicina() - Math.rint(comp.getKolicina())) > 1e-6)
                    ? String.format(Locale.GERMANY, "%.2f", comp.getKolicina())
                    : String.format(Locale.GERMANY, "%.0f", comp.getKolicina());
            labels[i] = kolStr + "x " + naziv;
            checked[i] = false;
        }

        String title = (paketNaziv != null ? paketNaziv : "Mix & Match paket")
                + (paketCena != null ? String.format(Locale.GERMANY, " (%.2f €)", paketCena) : "");

        new AlertDialog.Builder(context)
                .setTitle(title + " — izberite sestavine")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Potrdi", (dialog, which) -> {
                    List<CenikVrVrTp> selected = new ArrayList<>();
                    for (int i = 0; i < availableComponents.size(); i++) {
                        if (checked[i]) {
                            selected.add(availableComponents.get(i));
                        }
                    }
                    if (selected.isEmpty()) {
                        Toast.makeText(context, "Izberite vsaj eno sestavino paketa!", Toast.LENGTH_SHORT).show();
                        if (listener != null) listener.onCancelled();
                        return;
                    }
                    if (listener != null) {
                        listener.onComponentsSelected(selected);
                    }
                })
                .setNegativeButton("Prekliči", (dialog, which) -> {
                    if (listener != null) listener.onCancelled();
                })
                .show();
    }
}
