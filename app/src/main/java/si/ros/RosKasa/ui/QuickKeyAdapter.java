package si.ros.RosKasa.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import si.ros.RosKasa.R;

public class QuickKeyAdapter extends RecyclerView.Adapter<QuickKeyAdapter.ViewHolder> {

    public interface OnKeyClickListener {
        void onKeyClick(QuickKey key);
    }

    public static class QuickKey {
        public String title;
        public boolean isCategory;
        public boolean isBack;
        public String categoryGroup;
        public Integer categoryTargetId;
        public double price;
        public int tipkaId;
        public int nivo4Id;
        public int nacinProdaje;
        public double polnjenje;
        public int paket;
        public double ep;
        public Integer barva;
        public Integer dodatekId;
        public int kolicina;

        public QuickKey(String title, boolean isCategory, boolean isBack, String categoryGroup, double price) {
            this.title = title;
            this.isCategory = isCategory;
            this.isBack = isBack;
            this.categoryGroup = categoryGroup;
            this.price = price;
            this.nivo4Id = 0;
            this.nacinProdaje = 0;
            this.polnjenje = 1.0;
            this.paket = 0;
            this.ep = 1.0;
            this.barva = null;
            this.dodatekId = null;
            this.kolicina = 1;
        }
    }

    private int maxKeys = 25; // Portrait: 5 x 5 = 25, Landscape: 7 x 4 = 28
    private List<QuickKey> keys = new ArrayList<>();
    private final OnKeyClickListener listener;

    public QuickKeyAdapter(OnKeyClickListener listener) {
        this.listener = listener;
    }

    public void setMaxKeys(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    public void setKeys(List<QuickKey> inputKeys) {
        List<QuickKey> list = new ArrayList<>(inputKeys != null ? inputKeys : new ArrayList<>());
        if (list.size() > maxKeys) {
            list = new ArrayList<>(list.subList(0, maxKeys));
        }
        while (list.size() < maxKeys) {
            list.add(new QuickKey("", false, false, null, 0));
        }
        this.keys = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_quick_key, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        QuickKey key = keys.get(position);
        if (key == null || key.title == null || key.title.trim().isEmpty()) {
            holder.btnKey.setText("");
            holder.btnKey.setVisibility(View.VISIBLE);
            holder.btnKey.setEnabled(false);
            holder.btnKey.setBackgroundColor(Color.parseColor("#262A2D"));
            holder.btnKey.setOnClickListener(null);
            return;
        }

        holder.btnKey.setEnabled(true);
        holder.btnKey.setVisibility(View.VISIBLE);
        String displayTitle = key.title;
        if (!key.isBack && !key.isCategory && key.ep > 0 && Math.abs(key.ep - 1.0) > 1e-6) {
            String epStr = String.valueOf(key.ep).replace('.', ',');
            if (epStr.endsWith(",0")) epStr = epStr.substring(0, epStr.length() - 2);
            displayTitle = key.title + " [" + epStr + "]";
        }
        holder.btnKey.setText(displayTitle);

        if (key.isBack) {
            holder.btnKey.setBackgroundColor(Color.parseColor("#B71C1C")); // Dark Red for Back
            holder.btnKey.setTextColor(Color.WHITE);
        } else if (key.barva != null && key.barva != 0) {
            int androidColor = convertDelphiColor(key.barva);
            holder.btnKey.setBackgroundColor(androidColor);
            double lum = 0.299 * Color.red(androidColor) + 0.587 * Color.green(androidColor) + 0.114 * Color.blue(androidColor);
            holder.btnKey.setTextColor(lum > 155 ? Color.BLACK : Color.WHITE);
        } else if (key.isCategory) {
            holder.btnKey.setBackgroundColor(Color.parseColor("#555555")); // Grey for Category
            holder.btnKey.setTextColor(Color.WHITE);
        } else {
            holder.btnKey.setBackgroundColor(Color.parseColor("#1565C0")); // Blue for Article
            holder.btnKey.setTextColor(Color.WHITE);
        }

        holder.btnKey.setOnClickListener(v -> {
            if (listener != null) listener.onKeyClick(key);
        });
    }

    @Override
    public int getItemCount() {
        return keys.size();
    }

    private static int convertDelphiColor(int colorVal) {
        // Če ima že nastavljen alpha kanal (npr. ARGB iz FMX), ga uporabimo neposredno
        if ((colorVal & 0xFF000000) != 0) {
            return colorVal;
        }
        // Sicer je VCL TColor v formatu 0x00BBGGRR
        int r = colorVal & 0xFF;
        int g = (colorVal >> 8) & 0xFF;
        int b = (colorVal >> 16) & 0xFF;
        return Color.rgb(r, g, b);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        Button btnKey;

        ViewHolder(View itemView) {
            super(itemView);
            btnKey = itemView.findViewById(R.id.btnQuickKey);
        }
    }
}
