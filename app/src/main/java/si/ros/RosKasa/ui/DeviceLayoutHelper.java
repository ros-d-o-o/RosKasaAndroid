package si.ros.RosKasa.ui;

import android.content.Context;
import android.content.res.Configuration;

import si.ros.RosKasa.Globals;

/**
 * Pomožni razred za prepoznavanje tablice (sw600dp) in dinamični izračun stolpcev
 * za Mize, Hitre tipke in Plačila glede na dejansko velikost zaslona in orientacijo.
 *
 * Pravilo Android:
 * - smallestScreenWidthDp je krajša stranica zaslona v dp (enaka v portrait in landscape).
 * - Telefon ima vedno smallestScreenWidthDp < 600 (tudi v landscape).
 * - Tablica (>= 7") ima smallestScreenWidthDp >= 600.
 */
public final class DeviceLayoutHelper {

    public static final int TABLET_MIN_SW_DP = 600;

    private DeviceLayoutHelper() {}

    public static boolean isTablet(Context context) {
        if (Globals.getInstance().isForceTabletScreen()) {
            return true;
        }
        if (context == null) return false;
        Configuration cfg = context.getResources().getConfiguration();
        return cfg.smallestScreenWidthDp >= TABLET_MIN_SW_DP;
    }

    public static boolean isLandscape(Context context) {
        if (context == null) return false;
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public static boolean isTabletLandscape(Context context) {
        return isTablet(context) && isLandscape(context);
    }

    /**
     * Dinamično število stolpcev za mrežo miz (MizeFragment):
     * - Telefon Portrait: 4
     * - Tablica Portrait: 5–6 (glede na širino)
     * - Tablica Landscape: 6–8 (glede na širino)
     */
    public static int getMizeSpanCount(Context context) {
        if (context == null) return 4;
        Configuration cfg = context.getResources().getConfiguration();
        if (!isTablet(context)) {
            return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ? 6 : 4;
        }
        int widthDp = cfg.screenWidthDp;
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (widthDp >= 1000) return 8;
            if (widthDp >= 820) return 7;
            return 6;
        } else {
            return widthDp >= 720 ? 6 : 5;
        }
    }

    /**
     * Število stolpcev hitrih tipk (NarocilaFragment):
     * - Tablica Landscape (Delphi Windows vzorec): 7 stolpcev čez celotno širino
     * - Telefon / Tablica Portrait: 5 stolpcev
     */
    public static int getQuickKeysSpanCount(Context context) {
        return isTabletLandscape(context) ? 7 : 5;
    }

    /**
     * Največje število hitrih tipk na zaslonu:
     * - Tablica Landscape: 7 × 5 = 35 (skladno z Delphi Windows Landscape)
     * - Portrait / Telefon: 5 × 5 = 25
     */
    public static int getQuickKeysMaxCount(Context context) {
        return isTabletLandscape(context) ? 35 : 25;
    }
}
