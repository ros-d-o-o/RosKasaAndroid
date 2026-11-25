package si.ros.RosKasa.models;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KronologijaTp {
    private String opisOperacije;
    private int osebaId = 0;
    private int obratId = 0;
    private String datumUra;

    public KronologijaTp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
        this.datumUra = sdf.format(new Date());
    }

    public KronologijaTp(String opisOperacije, Integer osebaId, Integer obratId) {
        this();
        setOpisOperacije(opisOperacije);
        this.osebaId = (osebaId != null) ? osebaId : 0;
        this.obratId = (obratId != null) ? obratId : 0;
    }

    public String getOpisOperacije() {
        return opisOperacije;
    }

    public void setOpisOperacije(String opisOperacije) {
        if (opisOperacije == null) {
            this.opisOperacije = "";
        } else if (opisOperacije.length() > 950) {
            this.opisOperacije = opisOperacije.substring(0, 950);
        } else {
            this.opisOperacije = opisOperacije;
        }
    }

    public int getOsebaId() {
        return osebaId;
    }

    public void setOsebaId(int osebaId) {
        this.osebaId = osebaId;
    }

    public int getObratId() {
        return obratId;
    }

    public void setObratId(int obratId) {
        this.obratId = obratId;
    }

    public String getDatumUra() {
        return datumUra;
    }

    public void setDatumUra(String datumUra) {
        this.datumUra = datumUra;
    }
}
