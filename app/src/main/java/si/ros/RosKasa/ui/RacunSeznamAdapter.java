package si.ros.RosKasa.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.R;
import si.ros.RosKasa.models.OsebaTp;
import si.ros.RosKasa.models.RacunSeznamItem;

public class RacunSeznamAdapter extends RecyclerView.Adapter<RacunSeznamAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(RacunSeznamItem item, int position);
        void onItemDoubleClick(RacunSeznamItem item, int position);
    }

    private List<RacunSeznamItem> items = new ArrayList<>();
    private int selectedPosition = -1;
    private OnItemClickListener listener;
    private long lastClickTime = 0;

    public RacunSeznamAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<RacunSeznamItem> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        this.selectedPosition = -1;
        notifyDataSetChanged();
    }

    public RacunSeznamItem getSelectedItem() {
        if (selectedPosition >= 0 && selectedPosition < items.size()) {
            return items.get(selectedPosition);
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_racun_seznam, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RacunSeznamItem item = items.get(position);
        Globals g = Globals.getInstance();
        int kasiralId = item.getKasiral() != null ? item.getKasiral() : 0;
        boolean isMy = kasiralId > 0 && kasiralId == g.getTekocaOsebaId();
        boolean isOpenStatus1 = item.getStatus() != null && item.getStatus() == 1;
        boolean isPlacano = isRacunPlacan(item);

        String ownerLabel = resolveOwnerLabel(kasiralId);
        String naslovRacuna = ownerLabel.isEmpty()
                ? ("Račun #" + item.getRacunId())
                : ("Račun #" + item.getRacunId() + "  [" + ownerLabel + "]");
        holder.tvRacunId.setText(naslovRacuna);
        holder.tvMarker.setText("Miza: " + (item.getMarker() != null ? item.getMarker() : "-"));
        holder.tvZnesek.setText(isPlacano && isOpenStatus1
                ? (item.getFormattedZnesek() + " ✓")
                : item.getFormattedZnesek());
        String statusDesc = item.getStatusDescription();
        if (isOpenStatus1 && isPlacano) {
            statusDesc = statusDesc + " • Plačano ✓";
        }
        holder.tvStatus.setText(statusDesc);

        // Enaka barvna logika kot na Mizah (brez dodatnih SOAP klicev):
        // 1. ZELENA - odprt račun (STATUS=1), ki je že plačan (pripravljen za izpis)
        // 2. MODRA  - moj račun (trenutno prijavljen natakar)
        // 3. RDEČA  - račun drugega natakarja
        int baseBg;
        if (isOpenStatus1 && isPlacano) {
            baseBg = Color.parseColor("#2E7D32"); // Zelena - plačano / zaklenjeno
        } else if (isMy) {
            baseBg = Color.parseColor("#1565C0"); // Modra - moj račun
        } else if (kasiralId > 0) {
            baseBg = Color.parseColor("#B71C1C"); // Rdeča - račun drugega natakarja
        } else {
            baseBg = Color.parseColor("#333333");
        }

        boolean isSelected = (position == selectedPosition);
        if (isSelected) {
            // Svetlejši poudarek ob izbiri vrstice, da ostane jasno vidna izbrana vrstica
            holder.itemView.setBackgroundColor(Color.parseColor("#0288D1"));
            holder.itemView.setAlpha(1.0f);
        } else {
            holder.itemView.setBackgroundColor(baseBg);
            holder.itemView.setAlpha(0.92f);
        }

        holder.itemView.setOnClickListener(v -> {
            long clickTime = System.currentTimeMillis();
            int currentPos = holder.getAdapterPosition();
            if (currentPos == RecyclerView.NO_POSITION) return;

            selectedPosition = currentPos;
            notifyDataSetChanged();

            if (clickTime - lastClickTime < 400) {
                // Double tap detected
                if (listener != null) {
                    listener.onItemDoubleClick(item, currentPos);
                }
            } else {
                // Single tap
                if (listener != null) {
                    listener.onItemClick(item, currentPos);
                }
            }
            lastClickTime = clickTime;
        });
    }

    private boolean isRacunPlacan(RacunSeznamItem acc) {
        if (acc == null) return false;
        if (acc.isPlacan() || acc.hasAnyPayment()) return true;
        if (acc.getPlacano() != null && acc.getPlacano().compareTo(java.math.BigDecimal.ZERO) != 0) {
            return true;
        }
        Globals g = Globals.getInstance();
        java.math.BigDecimal cachedPl = g.getRacunPlacano(acc.getRacunId());
        if (cachedPl != null && cachedPl.compareTo(java.math.BigDecimal.ZERO) != 0) {
            return true;
        }
        si.ros.RosKasa.models.RacunTp cur = g.getCurrentRacun();
        if (cur != null && cur.getRacunId() == acc.getRacunId()
                && cur.getPlacano() != null && cur.getPlacano().compareTo(java.math.BigDecimal.ZERO) != 0) {
            return true;
        }
        return false;
    }

    private String resolveOwnerLabel(Integer kasiralId) {
        if (kasiralId == null || kasiralId <= 0) return "";
        for (OsebaTp o : Globals.getInstance().getCachedOsebje()) {
            if (o != null && o.getOsebaId() == kasiralId) {
                String naziv = o.getNaziv();
                String ini = o.getInicialke();
                if (naziv != null && !naziv.trim().isEmpty()) {
                    return naziv.trim();
                }
                if (ini != null && !ini.trim().isEmpty()) {
                    return ini.trim();
                }
                break;
            }
        }
        return String.valueOf(kasiralId);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvRacunId, tvMarker, tvZnesek, tvStatus;

        ViewHolder(View itemView) {
            super(itemView);
            tvRacunId = itemView.findViewById(R.id.tvRacunId);
            tvMarker = itemView.findViewById(R.id.tvMarker);
            tvZnesek = itemView.findViewById(R.id.tvZnesek);
            tvStatus = itemView.findViewById(R.id.tvStatus);
        }
    }
}
