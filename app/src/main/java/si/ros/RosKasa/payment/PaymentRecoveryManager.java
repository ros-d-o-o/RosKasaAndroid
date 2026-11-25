package si.ros.RosKasa.payment;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.math.BigDecimal;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.payment.models.PaytenResponse;
import si.ros.RosKasa.payment.models.SixTapResponse;

public class PaymentRecoveryManager {

    private static final String TAG = "PaymentRecoveryManager";

    public static final String PREFS_NAME = "SixTapRecoveryPrefs";

    public static final String KEY_SESSION_ID = "lastSessionId";
    public static final String KEY_RACUN_ID = "lastRacunId";
    public static final String KEY_ZNESEK_INT = "lastZnesekInt";

    public static final String KEY_INTENT_SESSION_ID = "lastIntentSessionId";
    public static final String KEY_INTENT_RACUN_ID = "lastIntentRacunId";
    public static final String KEY_INTENT_ZNESEK_INT = "lastIntentZnesekInt";

    public static final String KEY_RECOVER_SESSION_ID = "lastRecoverSessionId";
    public static final String KEY_RECOVER_RACUN_ID = "lastRecoverRacunId";
    public static final String KEY_RECOVER_ZNESEK_INT = "lastRecoverZnesekInt";
    public static final String KEY_RECOVER_INTENT_SESSION_ID = "lastRecoverIntentSessionId";
    public static final String KEY_RECOVER_INTENT_RACUN_ID = "lastRecoverIntentRacunId";
    public static final String KEY_RECOVER_INTENT_ZNESEK_INT = "lastRecoverIntentZnesekInt";
    public static final String KEY_RECOVER_RETRY_COUNT = "lastRecoverRetryCount";

    public static final int POS_STATE_NONE = 0;
    public static final int POS_STATE_PENDING_POS = 1;
    public static final int POS_STATE_POS_CONFIRMED = 2;
    public static final int POS_STATE_LOCAL_RECORDED = 3;
    public static final int POS_STATE_SERVER_SYNCED = 4;
    public static final int POS_STATE_COMPLETED = 5;

    public static final String KEY_POS_STATE = "lastPosState";
    public static final String KEY_POS_RACUN_ID = "lastPosRacunId";
    public static final String KEY_POS_SESSION_ID = "lastPosSessionId";
    public static final String KEY_POS_M_REF = "lastPosMRef";
    public static final String KEY_POS_AUTH_AMOUNT = "lastPosAuthAmount";
    public static final String KEY_POS_TIP_AMOUNT = "lastPosTipAmount";
    public static final String KEY_POS_BRAND_NAME = "lastPosBrandName";
    public static final String KEY_POS_CARD_NUMBER = "lastPosCardNumber";
    public static final String KEY_POS_SLIP_CLIENT = "lastPosSlipClient";
    public static final String KEY_POS_SLIP_MERCHANT = "lastPosSlipMerchant";
    public static final String KEY_POS_PLACILO_ID = "lastPosPlaciloId";
    public static final String KEY_POS_KUPEC_ID = "lastPosKupecId";
    public static final String KEY_POS_ZNESEK = "lastPosZnesek";

    // Payten 5-stopenjski stanjski avtomat (skladno z Delphi uPayTen.pas)
    public static final int PAYTEN_STATE_NONE           = 0;
    public static final int PAYTEN_STATE_PENDING_POS    = 1;
    public static final int PAYTEN_STATE_POS_CONFIRMED  = 2;
    public static final int PAYTEN_STATE_LOCAL_RECORDED = 3;
    public static final int PAYTEN_STATE_COMPLETED      = 4;

    public static final long PAYTEN_COOLDOWN_STRICT_SEC = 60L;
    public static final long PAYTEN_COOLDOWN_MAX_SEC    = 180L;

    public static final String KEY_PAYTEN_SESSION_ID   = "lastPayTenSessionId";
    public static final String KEY_PAYTEN_RACUN_ID     = "lastPayTenRacunId";
    public static final String KEY_PAYTEN_ZNESEK_INT   = "lastPayTenZnesekInt";
    public static final String KEY_PAYTEN_ZNESEK       = "lastPayTenZnesek";
    public static final String KEY_PAYTEN_NAPITNINA    = "lastPayTenNapitnina";
    public static final String KEY_PAYTEN_TIMESTAMP    = "lastPayTenTimestamp";
    public static final String KEY_PAYTEN_STATE        = "lastPayTenState";
    public static final String KEY_PAYTEN_M_REF        = "lastPayTenMRef";
    public static final String KEY_PAYTEN_AUTH_CODE    = "lastPayTenAuthCode";
    public static final String KEY_PAYTEN_AUTH_AMOUNT  = "lastPayTenAuthAmount";
    public static final String KEY_PAYTEN_CARD_NUMBER  = "lastPayTenCardNumber";
    public static final String KEY_PAYTEN_APP_ID       = "lastPayTenAppId";
    public static final String KEY_PAYTEN_RECEIPT      = "lastPayTenReceipt";
    public static final String KEY_PAYTEN_PLACILO_ID   = "lastPayTenPlaciloId";
    public static final String KEY_PAYTEN_KUPEC_ID     = "lastPayTenKupecId";
    public static final String KEY_PAYTEN_DO_NOT_PRINT = "lastPayTenDoNotPrint";

    public static class PaytenPendingInfo {
        public String sessionId = "";
        public String racunId = "";
        public String znesekInt = "";
        public int state = PAYTEN_STATE_NONE;
        public long elapsedSec = 0;

        public boolean isValid() {
            return racunId != null && !racunId.trim().isEmpty();
        }
    }

    public static class PendingPaymentInfo {
        public String sessionId = "";
        public String racunId = "";
        public String znesekInt = "";
        public String intentSessionId = "";
        public String intentRacunId = "";
        public String intentZnesekInt = "";

        public String getEffectiveSessionId() {
            if (intentSessionId != null && !intentSessionId.trim().isEmpty()) {
                return intentSessionId.trim();
            }
            return sessionId != null ? sessionId.trim() : "";
        }

        public String getEffectiveRacunId() {
            if (intentRacunId != null && !intentRacunId.trim().isEmpty()) {
                return intentRacunId.trim();
            }
            return racunId != null ? racunId.trim() : "";
        }

        public String getEffectiveZnesekInt() {
            if (intentZnesekInt != null && !intentZnesekInt.trim().isEmpty()) {
                return intentZnesekInt.trim();
            }
            return znesekInt != null ? znesekInt.trim() : "";
        }

        public boolean isValid() {
            return !getEffectiveSessionId().isEmpty() && !getEffectiveRacunId().isEmpty();
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void savePendingPayment(Context context, String aSessionId, String aRacunId, String aZnesekInt,
                                          String iSessionId, String iRacunId, String iZnesekInt) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        if (aSessionId != null && !aSessionId.isEmpty()) editor.putString(KEY_SESSION_ID, aSessionId);
        if (aRacunId != null && !aRacunId.isEmpty()) editor.putString(KEY_RACUN_ID, aRacunId);
        if (aZnesekInt != null && !aZnesekInt.isEmpty()) editor.putString(KEY_ZNESEK_INT, aZnesekInt);

        if (iSessionId != null && !iSessionId.isEmpty()) editor.putString(KEY_INTENT_SESSION_ID, iSessionId);
        if (iRacunId != null && !iRacunId.isEmpty()) editor.putString(KEY_INTENT_RACUN_ID, iRacunId);
        if (iZnesekInt != null && !iZnesekInt.isEmpty()) editor.putString(KEY_INTENT_ZNESEK_INT, iZnesekInt);

        editor.apply();
    }

    public static PendingPaymentInfo tryGetPendingPayment(Context context) {
        SharedPreferences sp = getPrefs(context);
        PendingPaymentInfo info = new PendingPaymentInfo();
        info.sessionId = sp.getString(KEY_SESSION_ID, "");
        info.racunId = sp.getString(KEY_RACUN_ID, "");
        info.znesekInt = sp.getString(KEY_ZNESEK_INT, "");
        info.intentSessionId = sp.getString(KEY_INTENT_SESSION_ID, "");
        info.intentRacunId = sp.getString(KEY_INTENT_RACUN_ID, "");
        info.intentZnesekInt = sp.getString(KEY_INTENT_ZNESEK_INT, "");
        return info;
    }

    public static void clearPendingPayment(Context context) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.remove(KEY_SESSION_ID);
        editor.remove(KEY_RACUN_ID);
        editor.remove(KEY_ZNESEK_INT);
        editor.remove(KEY_INTENT_SESSION_ID);
        editor.remove(KEY_INTENT_RACUN_ID);
        editor.remove(KEY_INTENT_ZNESEK_INT);
        editor.apply();
    }

    public static void saveRecoveryPayment(Context context, String aSessionId, String aRacunId, String aZnesekInt,
                                           String iSessionId, String iRacunId, String iZnesekInt) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        if (aSessionId != null && !aSessionId.isEmpty()) editor.putString(KEY_RECOVER_SESSION_ID, aSessionId);
        if (aRacunId != null && !aRacunId.isEmpty()) editor.putString(KEY_RECOVER_RACUN_ID, aRacunId);
        if (aZnesekInt != null && !aZnesekInt.isEmpty()) editor.putString(KEY_RECOVER_ZNESEK_INT, aZnesekInt);

        if (iSessionId != null && !iSessionId.isEmpty()) editor.putString(KEY_RECOVER_INTENT_SESSION_ID, iSessionId);
        if (iRacunId != null && !iRacunId.isEmpty()) editor.putString(KEY_RECOVER_INTENT_RACUN_ID, iRacunId);
        if (iZnesekInt != null && !iZnesekInt.isEmpty()) editor.putString(KEY_RECOVER_INTENT_ZNESEK_INT, iZnesekInt);

        editor.apply();
    }

    public static PendingPaymentInfo tryGetRecoveryPayment(Context context) {
        SharedPreferences sp = getPrefs(context);
        PendingPaymentInfo info = new PendingPaymentInfo();
        info.sessionId = sp.getString(KEY_RECOVER_SESSION_ID, "");
        info.racunId = sp.getString(KEY_RECOVER_RACUN_ID, "");
        info.znesekInt = sp.getString(KEY_RECOVER_ZNESEK_INT, "");
        info.intentSessionId = sp.getString(KEY_RECOVER_INTENT_SESSION_ID, "");
        info.intentRacunId = sp.getString(KEY_RECOVER_INTENT_RACUN_ID, "");
        info.intentZnesekInt = sp.getString(KEY_RECOVER_INTENT_ZNESEK_INT, "");
        return info;
    }

    public static void clearRecoveryPayment(Context context) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.remove(KEY_RECOVER_SESSION_ID);
        editor.remove(KEY_RECOVER_RACUN_ID);
        editor.remove(KEY_RECOVER_ZNESEK_INT);
        editor.remove(KEY_RECOVER_INTENT_SESSION_ID);
        editor.remove(KEY_RECOVER_INTENT_RACUN_ID);
        editor.remove(KEY_RECOVER_INTENT_ZNESEK_INT);
        editor.remove(KEY_RECOVER_RETRY_COUNT);
        editor.apply();
    }

    public static int getRecoveryRetryCount(Context context) {
        return getPrefs(context).getInt(KEY_RECOVER_RETRY_COUNT, 0);
    }

    public static int incrementRecoveryRetryCount(Context context) {
        SharedPreferences sp = getPrefs(context);
        int current = sp.getInt(KEY_RECOVER_RETRY_COUNT, 0) + 1;
        sp.edit().putInt(KEY_RECOVER_RETRY_COUNT, current).apply();
        return current;
    }

    public static void resetRecoveryRetryCount(Context context) {
        getPrefs(context).edit().putInt(KEY_RECOVER_RETRY_COUNT, 0).apply();
    }

    public static void saveConfirmedPOSTransaction(Context context, SixTapResponse resp, int state) {
        if (resp == null) return;
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putInt(KEY_POS_STATE, state);
        editor.putInt(KEY_POS_RACUN_ID, resp.getpRacunId());
        editor.putString(KEY_POS_SESSION_ID, resp.getpWpiSessionId());
        editor.putString(KEY_POS_M_REF, resp.getPaymentSolutionReference());
        editor.putString(KEY_POS_AUTH_AMOUNT, resp.getAuthorizedAmount());
        editor.putString(KEY_POS_TIP_AMOUNT, resp.getTipAmount());
        editor.putString(KEY_POS_BRAND_NAME, resp.getBrandName());
        editor.putString(KEY_POS_CARD_NUMBER, resp.getCardnumber());
        editor.putString(KEY_POS_SLIP_CLIENT, resp.getClient());
        editor.putString(KEY_POS_SLIP_MERCHANT, resp.getMerchant());
        editor.putInt(KEY_POS_PLACILO_ID, resp.getpPlaciloId());
        editor.putInt(KEY_POS_KUPEC_ID, resp.getpKupecId());
        editor.putString(KEY_POS_ZNESEK, resp.getpZnesek() != null ? resp.getpZnesek().toPlainString() : "0");
        editor.apply();
    }

    public static boolean tryGetConfirmedPOSTransaction(Context context, SixTapResponse outResp, int[] outState) {
        SharedPreferences sp = getPrefs(context);
        int state = sp.getInt(KEY_POS_STATE, POS_STATE_NONE);
        if (state < POS_STATE_POS_CONFIRMED) {
            return false;
        }
        if (outState != null && outState.length > 0) {
            outState[0] = state;
        }
        if (outResp != null) {
            outResp.setpRacunId(sp.getInt(KEY_POS_RACUN_ID, 0));
            outResp.setpWpiSessionId(sp.getString(KEY_POS_SESSION_ID, ""));
            outResp.setPaymentSolutionReference(sp.getString(KEY_POS_M_REF, ""));
            outResp.setAuthorizedAmount(sp.getString(KEY_POS_AUTH_AMOUNT, ""));
            outResp.setTipAmount(sp.getString(KEY_POS_TIP_AMOUNT, ""));
            outResp.setBrandName(sp.getString(KEY_POS_BRAND_NAME, ""));
            outResp.setCardnumber(sp.getString(KEY_POS_CARD_NUMBER, ""));
            outResp.setClient(sp.getString(KEY_POS_SLIP_CLIENT, ""));
            outResp.setMerchant(sp.getString(KEY_POS_SLIP_MERCHANT, ""));
            outResp.setpPlaciloId(sp.getInt(KEY_POS_PLACILO_ID, 0));
            outResp.setpKupecId(sp.getInt(KEY_POS_KUPEC_ID, 0));
            try {
                outResp.setpZnesek(new BigDecimal(sp.getString(KEY_POS_ZNESEK, "0")));
            } catch (Exception ignored) {
                outResp.setpZnesek(BigDecimal.ZERO);
            }
            outResp.setResult("WPI_RESULT_SUCCESS");
        }
        return true;
    }

    public static void setPOSTransactionState(Context context, int state) {
        getPrefs(context).edit().putInt(KEY_POS_STATE, state).apply();
    }

    public static int getPOSTransactionState(Context context) {
        return getPrefs(context).getInt(KEY_POS_STATE, POS_STATE_NONE);
    }

    public static void clearConfirmedPOSTransaction(Context context) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.remove(KEY_POS_STATE);
        editor.remove(KEY_POS_RACUN_ID);
        editor.remove(KEY_POS_SESSION_ID);
        editor.remove(KEY_POS_M_REF);
        editor.remove(KEY_POS_AUTH_AMOUNT);
        editor.remove(KEY_POS_TIP_AMOUNT);
        editor.remove(KEY_POS_BRAND_NAME);
        editor.remove(KEY_POS_CARD_NUMBER);
        editor.remove(KEY_POS_SLIP_CLIENT);
        editor.remove(KEY_POS_SLIP_MERCHANT);
        editor.remove(KEY_POS_PLACILO_ID);
        editor.remove(KEY_POS_KUPEC_ID);
        editor.remove(KEY_POS_ZNESEK);
        editor.apply();
    }

    // ======================== PAYTEN CRASH RECOVERY ========================

    public static void savePaytenPendingPayment(Context context, String sessionId, String racunId, String znesekInt, int state) {
        if (context == null || racunId == null || racunId.trim().isEmpty()) return;
        long nowEpochSec = System.currentTimeMillis() / 1000L;
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putString(KEY_PAYTEN_SESSION_ID, sessionId != null ? sessionId : "");
        editor.putString(KEY_PAYTEN_RACUN_ID, racunId.trim());
        editor.putString(KEY_PAYTEN_ZNESEK_INT, znesekInt != null ? znesekInt : "0");
        editor.putLong(KEY_PAYTEN_TIMESTAMP, nowEpochSec);
        editor.putInt(KEY_PAYTEN_STATE, state);
        editor.apply();
    }

    public static PaytenPendingInfo tryGetPaytenPendingPayment(Context context) {
        PaytenPendingInfo info = new PaytenPendingInfo();
        if (context == null) return info;
        SharedPreferences sp = getPrefs(context);
        if (!sp.contains(KEY_PAYTEN_RACUN_ID)) return info;

        info.sessionId = sp.getString(KEY_PAYTEN_SESSION_ID, "");
        info.racunId = sp.getString(KEY_PAYTEN_RACUN_ID, "");
        info.znesekInt = sp.getString(KEY_PAYTEN_ZNESEK_INT, "");
        info.state = sp.getInt(KEY_PAYTEN_STATE, PAYTEN_STATE_NONE);
        long ts = sp.getLong(KEY_PAYTEN_TIMESTAMP, 0L);
        if (ts > 0L) {
            long nowEpochSec = System.currentTimeMillis() / 1000L;
            info.elapsedSec = Math.max(0L, nowEpochSec - ts);
        } else {
            info.elapsedSec = 999999L;
        }
        return info;
    }

    public static void saveConfirmedPaytenTransaction(Context context, PaytenResponse resp, int state) {
        if (context == null || resp == null) return;
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putInt(KEY_PAYTEN_STATE, state);
        editor.putString(KEY_PAYTEN_RACUN_ID, String.valueOf(resp.getpRacunId()));
        editor.putString(KEY_PAYTEN_M_REF, resp.getPaymentSolutionReference());
        editor.putString(KEY_PAYTEN_AUTH_CODE, resp.getpAuthorizationCode());
        editor.putString(KEY_PAYTEN_AUTH_AMOUNT, resp.getAuthorizedAmount());
        editor.putString(KEY_PAYTEN_CARD_NUMBER, resp.getCardNumber());
        editor.putString(KEY_PAYTEN_APP_ID, resp.getApplicationIdentifier());
        editor.putString(KEY_PAYTEN_RECEIPT, resp.getReceipt());
        editor.putInt(KEY_PAYTEN_PLACILO_ID, resp.getpPlaciloId());
        editor.putInt(KEY_PAYTEN_KUPEC_ID, resp.getpKupecId());
        editor.putString(KEY_PAYTEN_ZNESEK, resp.getpZnesek() != null ? resp.getpZnesek().toPlainString() : "0");
        editor.putString(KEY_PAYTEN_NAPITNINA, resp.getpNapitnina() != null ? resp.getpNapitnina().toPlainString() : "0");
        editor.putBoolean(KEY_PAYTEN_DO_NOT_PRINT, resp.ispDoNotPrint());
        editor.apply();
    }

    public static boolean tryGetConfirmedPaytenTransaction(Context context, PaytenResponse outResp, int[] outState) {
        if (context == null) return false;
        SharedPreferences sp = getPrefs(context);
        if (!sp.contains(KEY_PAYTEN_STATE)) return false;
        int state = sp.getInt(KEY_PAYTEN_STATE, PAYTEN_STATE_NONE);
        if (state != PAYTEN_STATE_POS_CONFIRMED && state != PAYTEN_STATE_LOCAL_RECORDED) {
            return false;
        }
        if (outState != null && outState.length > 0) {
            outState[0] = state;
        }
        int racunId = 0;
        try {
            racunId = Integer.parseInt(sp.getString(KEY_PAYTEN_RACUN_ID, "0").trim());
        } catch (Exception ignored) {}
        String mRef = sp.getString(KEY_PAYTEN_M_REF, "");
        if (outResp != null) {
            outResp.setpRacunId(racunId);
            outResp.setPaymentSolutionReference(mRef);
            outResp.setPaymentIdentificator(mRef);
            outResp.setpAuthorizationCode(sp.getString(KEY_PAYTEN_AUTH_CODE, ""));
            String authAmt = sp.getString(KEY_PAYTEN_AUTH_AMOUNT, "");
            outResp.setAuthorizedAmount(authAmt);
            if (!authAmt.isEmpty()) {
                try {
                    outResp.setZnesekPOS(new BigDecimal(authAmt.replace(",", ".").trim()));
                } catch (Exception ignored) {}
            }
            outResp.setCardNumber(sp.getString(KEY_PAYTEN_CARD_NUMBER, ""));
            outResp.setApplicationIdentifier(sp.getString(KEY_PAYTEN_APP_ID, ""));
            String receipt = sp.getString(KEY_PAYTEN_RECEIPT, "");
            outResp.setReceipt(receipt);
            outResp.setClient(receipt);
            outResp.setpPlaciloId(sp.getInt(KEY_PAYTEN_PLACILO_ID, 0));
            outResp.setpKupecId(sp.getInt(KEY_PAYTEN_KUPEC_ID, 0));
            outResp.setpDoNotPrint(sp.getBoolean(KEY_PAYTEN_DO_NOT_PRINT, false));
            try {
                outResp.setpZnesek(new BigDecimal(sp.getString(KEY_PAYTEN_ZNESEK, "0").replace(",", ".").trim()));
            } catch (Exception ignored) {
                outResp.setpZnesek(BigDecimal.ZERO);
            }
            try {
                outResp.setpNapitnina(new BigDecimal(sp.getString(KEY_PAYTEN_NAPITNINA, "0").replace(",", ".").trim()));
            } catch (Exception ignored) {
                outResp.setpNapitnina(BigDecimal.ZERO);
            }
            if (outResp.getZnesekPOS().compareTo(BigDecimal.ZERO) == 0) {
                outResp.setZnesekPOS(outResp.getpZnesek().add(outResp.getpNapitnina()));
            }
            outResp.setpOperacija("purchase");
            outResp.setResult("OK");
            outResp.setCode("00");
        }
        return racunId > 0 && mRef != null && !mRef.trim().isEmpty();
    }

    public static void setPaytenState(Context context, int state) {
        setPaytenState(context, state, null, null);
    }

    public static void setPaytenState(Context context, int state, String mRef, String authCode) {
        if (context == null) return;
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putInt(KEY_PAYTEN_STATE, state);
        if (mRef != null && !mRef.isEmpty()) {
            editor.putString(KEY_PAYTEN_M_REF, mRef);
        }
        if (authCode != null && !authCode.isEmpty()) {
            editor.putString(KEY_PAYTEN_AUTH_CODE, authCode);
        }
        editor.apply();
    }

    public static int getPaytenState(Context context) {
        if (context == null) return PAYTEN_STATE_NONE;
        return getPrefs(context).getInt(KEY_PAYTEN_STATE, PAYTEN_STATE_NONE);
    }

    public static void clearPaytenPendingPayment(Context context) {
        Globals.getInstance().setPayTenIntransaction(false);
        if (context == null) return;
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.remove(KEY_PAYTEN_SESSION_ID);
        editor.remove(KEY_PAYTEN_RACUN_ID);
        editor.remove(KEY_PAYTEN_ZNESEK_INT);
        editor.remove(KEY_PAYTEN_ZNESEK);
        editor.remove(KEY_PAYTEN_NAPITNINA);
        editor.remove(KEY_PAYTEN_TIMESTAMP);
        editor.remove(KEY_PAYTEN_STATE);
        editor.remove(KEY_PAYTEN_M_REF);
        editor.remove(KEY_PAYTEN_AUTH_CODE);
        editor.remove(KEY_PAYTEN_AUTH_AMOUNT);
        editor.remove(KEY_PAYTEN_CARD_NUMBER);
        editor.remove(KEY_PAYTEN_APP_ID);
        editor.remove(KEY_PAYTEN_RECEIPT);
        editor.remove(KEY_PAYTEN_PLACILO_ID);
        editor.remove(KEY_PAYTEN_KUPEC_ID);
        editor.remove(KEY_PAYTEN_DO_NOT_PRINT);
        editor.apply();
    }

    /**
     * Počisti vse shranjene podatke za okrevanje plačil (uporabno ob uspešni transakciji ali ob Testu tiskanja).
     */
    public static void clearAllRecoveryData(Context context) {
        clearPendingPayment(context);
        clearRecoveryPayment(context);
        clearConfirmedPOSTransaction(context);
        clearPaytenPendingPayment(context);
        Globals.getInstance().setSixtapintransaction(false);
        Globals.getInstance().setPayTenIntransaction(false);
        Globals.getInstance().setZadnjiPayTenARacunId(0);
        Log.i(TAG, "clearAllRecoveryData: Vsi začasni podatki plačilnih sej in flagi so bili uspešno ponastavljeni.");
    }
}
