package si.ros.RosKasa.payment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.payment.models.PaytenResponse;

public class PaytenPaymentService {

    private static final String TAG = "PaytenPaymentService";
    public static final int REQUEST_CODE_PAYTEN = 1;

    public interface PaytenCallback {
        void onSuccess(PaytenResponse response);
        void onError(String code, String message);
        void onCanceled();
    }

    public interface PaytenLaunchCallback {
        void onProceedLaunch();
        void onRecoverConfirmed(PaytenResponse confirmedResp);
        void onBlocked(String message);
    }

    /**
     * Preveri ali je PayTen transakcija trenutno v teku (s 60s varovalom proti trajni blokadi,
     * skladno z Delphi uPayTen.IsPayTenInTransaction).
     */
    public static boolean isPayTenInTransaction(Context context) {
        Globals g = Globals.getInstance();
        if (!g.isPayTenA()) {
            return false;
        }
        if (g.isPayTenIntransaction()) {
            PaymentRecoveryManager.PaytenPendingInfo pending = PaymentRecoveryManager.tryGetPaytenPendingPayment(context);
            if (pending.isValid() && pending.elapsedSec >= PaymentRecoveryManager.PAYTEN_COOLDOWN_STRICT_SEC) {
                g.setPayTenIntransaction(false);
            }
        }
        return g.isPayTenIntransaction();
    }

    /**
     * 5-stopenjska zaščita pred podvojenim bremenjenjem in crash recovery pred zagonom PayTen purchase intenta
     * (skladno z Delphi uPayTen.PayTenAPI2).
     */
    public static void prepareAndCheckPurchase(Context context, int racunId, int placiloId, BigDecimal amount,
                                               PaytenLaunchCallback callback) {
        if (callback == null) return;
        Globals g = Globals.getInstance();
        if (!g.isPayTenA()) {
            callback.onBlocked("PayTen ni vklopljen.");
            return;
        }

        final String reqRacunIdStr = String.valueOf(racunId);

        // 1. Preverjanje aktivnega intenta v pomnilniku (s 60s varovalom)
        if (isPayTenInTransaction(context)) {
            g.vpisiKronologijo("PAYTEN INTENT BLOKIRAN: R:" + reqRacunIdStr + " Razlog:V_TEKU");
            callback.onBlocked("Plačilo PayTen je že v teku. Počakajte na zaključek.");
            return;
        }

        // 2. Trajna kontrola SharedPreferences in časovno varovalo (60s / 180s)
        PaymentRecoveryManager.PaytenPendingInfo pending = PaymentRecoveryManager.tryGetPaytenPendingPayment(context);
        if (pending.isValid()) {
            if (reqRacunIdStr.equals(pending.racunId)) {
                // A: Plačilo za ta račun je bilo na POS terminalu že odobreno, a sinhronizacija ni zaključena
                if (pending.state == PaymentRecoveryManager.PAYTEN_STATE_POS_CONFIRMED
                        || pending.state == PaymentRecoveryManager.PAYTEN_STATE_LOCAL_RECORDED) {
                    g.vpisiKronologijo("PAYTEN INTENT BLOKIRAN: R:" + reqRacunIdStr + " Razlog:ZE_POTRJENO_NA_POS_STATE_" + pending.state);
                    PaytenResponse confirmedResp = new PaytenResponse();
                    int[] confirmedState = new int[1];
                    if (PaymentRecoveryManager.tryGetConfirmedPaytenTransaction(context, confirmedResp, confirmedState)
                            && confirmedResp.getpRacunId() == racunId) {
                        callback.onRecoverConfirmed(confirmedResp);
                        return;
                    }
                    callback.onBlocked("Za ta račun je plačilo PayTen že potrjeno na POS terminalu. Ponovno plačilo ni dovoljeno.");
                    return;
                }

                // B: Plačilo za ta račun je že zaključeno
                if (pending.state == PaymentRecoveryManager.PAYTEN_STATE_COMPLETED) {
                    g.vpisiKronologijo("PAYTEN INTENT BLOKIRAN: R:" + reqRacunIdStr + " Razlog:ZE_PLACANO_SHARED_PREFS");
                    callback.onBlocked("Za ta račun je plačilo PayTen že izvedeno! Ponovno plačilo ni dovoljeno.");
                    return;
                }

                // C: Prejšnji intent je v stanju PENDING_POS
                if (pending.state == PaymentRecoveryManager.PAYTEN_STATE_PENDING_POS) {
                    if (pending.elapsedSec < PaymentRecoveryManager.PAYTEN_COOLDOWN_STRICT_SEC) {
                        g.vpisiKronologijo("PAYTEN INTENT BLOKIRAN: R:" + reqRacunIdStr + " Razlog:CASOVNI_PRAG_" + pending.elapsedSec + "s");
                        callback.onBlocked("Plačilo PayTen za račun R:" + reqRacunIdStr + " je še v teku (" + pending.elapsedSec + "s). Počakajte na POS terminal.");
                        return;
                    } else if (pending.elapsedSec <= PaymentRecoveryManager.PAYTEN_COOLDOWN_MAX_SEC) {
                        // Varnostni dialog (60-180s): ker PayTen nima LAST_TRANSACTION, vprašamo natakarja
                        g.vpisiKronologijo("PAYTEN INTENT DIALOG: R:" + reqRacunIdStr + " PretekliCas:" + pending.elapsedSec + "s");
                        String msg = String.format(Locale.GERMANY,
                                "Za račun R:%s je bilo pred %d sekundami že sproženo plačilo na POS terminalu (%.2f €).\n" +
                                        "Ali je stranka kartico že uspešno prislonila in je bilo plačilo na POS odobreno?",
                                reqRacunIdStr, pending.elapsedSec, amount != null ? amount : BigDecimal.ZERO);
                        new androidx.appcompat.app.AlertDialog.Builder(context)
                                .setTitle("Potrditev PayTen plačila")
                                .setMessage(msg)
                                .setCancelable(false)
                                .setPositiveButton("Da", (d, w) -> {
                                    g.vpisiKronologijo("PAYTEN ROCNA POTRDITEV: R:" + reqRacunIdStr + " UporabnikPotrdilUspeh:DA PretekliCas:" + pending.elapsedSec + "s");
                                    PaytenResponse manualResp = new PaytenResponse();
                                    manualResp.setpRacunId(racunId);
                                    manualResp.setpPlaciloId(placiloId);
                                    manualResp.setpZnesek(amount);
                                    manualResp.setZnesekPOS(amount);
                                    manualResp.setAuthorizedAmount(amount != null ? amount.toPlainString() : "0");
                                    manualResp.setPaymentSolutionReference("MANUAL-" + reqRacunIdStr);
                                    manualResp.setPaymentIdentificator("MANUAL-" + reqRacunIdStr);
                                    manualResp.setpOperacija("purchase");
                                    manualResp.setResult("OK");
                                    manualResp.setCode("00");
                                    PaymentRecoveryManager.saveConfirmedPaytenTransaction(context, manualResp, PaymentRecoveryManager.PAYTEN_STATE_POS_CONFIRMED);
                                    callback.onRecoverConfirmed(manualResp);
                                })
                                .setNegativeButton("Ne", (d, w) -> {
                                    g.vpisiKronologijo("PAYTEN ROCNA SPROSTITEV: R:" + reqRacunIdStr + " UporabnikPotrdilNeuspeh:DA PretekliCas:" + pending.elapsedSec + "s");
                                    PaymentRecoveryManager.clearPaytenPendingPayment(context);
                                    callback.onProceedLaunch();
                                })
                                .show();
                        return;
                    } else {
                        // Več kot 180 sekund: samodejna sprostitev zapore
                        g.vpisiKronologijo("PAYTEN AVTOMATSKA SPROSTITEV: R:" + reqRacunIdStr + " PretekliCas:" + pending.elapsedSec + "s (>180s)");
                        PaymentRecoveryManager.clearPaytenPendingPayment(context);
                    }
                }
            } else {
                // Drug račun: če je na POS terminalu še aktivna transakcija (< 60s), zavrnemo
                if (pending.state == PaymentRecoveryManager.PAYTEN_STATE_PENDING_POS
                        && pending.elapsedSec < PaymentRecoveryManager.PAYTEN_COOLDOWN_STRICT_SEC) {
                    g.vpisiKronologijo("PAYTEN INTENT BLOKIRAN: R:" + reqRacunIdStr + " Razlog:V_TEKU_DRUG_RACUN_R:" + pending.racunId + "_" + pending.elapsedSec + "s");
                    callback.onBlocked("Plačilo PayTen za drug račun (R:" + pending.racunId + ") je še v teku (" + pending.elapsedSec + "s). Počakajte na zaključek.");
                    return;
                } else {
                    if (pending.state == PaymentRecoveryManager.PAYTEN_STATE_POS_CONFIRMED
                            || pending.state == PaymentRecoveryManager.PAYTEN_STATE_LOCAL_RECORDED) {
                        g.vpisiKronologijo("PAYTEN OPOZORILO: Nov nakup za R:" + reqRacunIdStr + " čisti stanje prejšnjega R:" + pending.racunId + " State:" + pending.state);
                    }
                    PaymentRecoveryManager.clearPaytenPendingPayment(context);
                }
            }
        }

        callback.onProceedLaunch();
    }

    public static Intent buildPaymentIntent(Context context, int racunId, int pozicijaId, BigDecimal amount,
                                            String operation, String authCode) {
        Globals g = Globals.getInstance();
        String pin = g.getPayTenAPin();
        String pkg = g.getPayTenARosPackage();
        if (pkg == null || pkg.isEmpty()) {
            pkg = context != null ? context.getPackageName() : "si.ros.RosKasa";
        }
        String activityMain = g.getPayTenAActivityMain();
        if (activityMain == null || activityMain.isEmpty()) {
            activityMain = "com.payten.nlb.slovenia";
        }
        String activitySplash = g.getPayTenAActivities();
        if (activitySplash == null || activitySplash.isEmpty()) {
            activitySplash = "com.payten.nlb.slovenia.activities.SplashActivity";
        }

        // Znesek v centih (npr. 15.50 -> 1550)
        int amountCents = amount.multiply(new BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).intValue();
        String transactionId = racunId + "-" + pozicijaId;
        String opClass = (operation != null && !operation.isEmpty()) ? operation : "purchase";

        String jsonRequest = buildJsonRequest(pin, String.valueOf(amountCents), pkg, "POS", opClass, authCode, transactionId);
        Log.i(TAG, "PayTen Zahteva za R:" + racunId + ": " + jsonRequest);

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(activityMain, activitySplash));
        intent.setAction(activitySplash);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("REQUEST_JSON_STRING", jsonRequest);

        // Shrani stanje PENDING_POS v Payten recovery shrambo (samo za purchase, skladno z Delphi uPayTen.pas)
        if ("purchase".equalsIgnoreCase(opClass)) {
            PaymentRecoveryManager.clearPaytenPendingPayment(context);
            PaymentRecoveryManager.savePaytenPendingPayment(context, transactionId, String.valueOf(racunId), String.valueOf(amountCents), PaymentRecoveryManager.PAYTEN_STATE_PENDING_POS);
            g.vpisiKronologijo("PAYTEN SHRAMBA STANJA: R:" + racunId + " NovoStanje:PENDING");
        }
        g.setPayTenIntransaction(true);
        g.setZadnjiPayTenARacunId(racunId);
        g.setZadnjaPayTenAPozicijaId(pozicijaId);

        return intent;
    }

    private static String buildJsonRequest(String pin, String amount, String packageName,
                                           String transactionType, String transactionClass,
                                           String authorizationCode, String merchantUniqueID) {
        try {
            JSONObject root = new JSONObject();
            JSONObject request = new JSONObject();
            request.put("pin", pin != null ? pin : "");
            request.put("amount", amount != null ? amount : "0");
            request.put("packageName", packageName != null ? packageName : "");
            request.put("transactionType", transactionType != null ? transactionType : "POS");
            request.put("transactionClass", transactionClass != null ? transactionClass : "purchase");
            if (authorizationCode != null && !authorizationCode.isEmpty()) {
                request.put("authorizationCode", authorizationCode);
            }
            request.put("merchantUniqueID", merchantUniqueID != null ? merchantUniqueID : "");
            root.put("request", request);
            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri sestavi JSON za PayTen: " + e.getMessage(), e);
            si.ros.RosKasa.Globals.getInstance().vpisiNapakoKronologija("PayTen JSON", e);
            return "{}";
        }
    }

    public static PaytenResponse parseResponseIntent(Intent data) {
        PaytenResponse resp = new PaytenResponse();
        if (data == null) {
            resp.setResult("Napaka");
            resp.setpFault("Intent data is null");
            return resp;
        }

        Bundle extras = data.getExtras();
        if (extras == null) {
            resp.setResult("Napaka");
            resp.setpFault("Extras is null");
            return resp;
        }

        CharSequence seq = extras.getCharSequence("RESPONSE_JSON_STRING");
        if (seq == null) {
            resp.setResult("Napaka");
            resp.setpFault("RESPONSE_JSON_STRING ni najden");
            return resp;
        }

        String jsonString = seq.toString();
        Log.i(TAG, "PayTen Odgovor JSON: " + jsonString);

        try {
            JSONObject root = new JSONObject(jsonString);
            JSONObject responseObj = optObjectCI(root, "response");
            if (responseObj != null) {
                String payId = optStringCI(responseObj, "paymentIdentificator");
                if (!payId.isEmpty()) {
                    resp.setPaymentIdentificator(payId);
                    resp.setPaymentSolutionReference(payId);
                }

                JSONObject statusObj = optObjectCI(responseObj, "status");
                if (statusObj != null) {
                    resp.setCode(optStringCI(statusObj, "code"));
                    resp.setMessage(optStringCI(statusObj, "message"));
                    resp.setpMessage(resp.getMessage());

                    if ("00".equals(resp.getCode())) {
                        resp.setResult("OK");
                        String receiptDataStr = optStringCI(statusObj, "receiptData");
                        resp.setpReceiptData(receiptDataStr);

                        if (!receiptDataStr.isEmpty()) {
                            JSONObject receiptObj = new JSONObject(receiptDataStr);
                            resp.setApplicationIdentifier(optStringCI(receiptObj, "aid"));
                            resp.setAuthorizedAmount(optStringCI(receiptObj, "amount"));
                            String tipStr = optStringCI(receiptObj, "tipAmount");
                            if (tipStr.isEmpty()) tipStr = optStringCI(receiptObj, "tip");
                            if (tipStr.isEmpty()) tipStr = optStringCI(statusObj, "tipAmount");
                            if (tipStr.isEmpty()) tipStr = optStringCI(responseObj, "tipAmount");
                            resp.setTipAmount(tipStr);
                            resp.setApplicationLabel(optStringCI(receiptObj, "applicationLabel"));
                            resp.setpAuthorizationCode(optStringCI(receiptObj, "authorizationCode"));
                            resp.setBankName(optStringCI(receiptObj, "bankName"));
                            resp.setCardNumber(optStringCI(receiptObj, "cardNumber"));
                            resp.setTimestamp(optStringCI(receiptObj, "dateTime"));
                            resp.setMerchantId(optStringCI(receiptObj, "merchantId"));
                            resp.setMerchant(optStringCI(receiptObj, "merchantName"));
                            resp.setOperationName(optStringCI(receiptObj, "operationName"));
                            resp.setResponse(optStringCI(receiptObj, "response"));
                            resp.setRrn(optStringCI(receiptObj, "rrn"));
                            resp.setStatus(optStringCI(receiptObj, "status"));
                            resp.setTerminalId(optStringCI(receiptObj, "terminalId"));

                            // Parsiranje zneska v BigDecimal
                            try {
                                if (!resp.getAuthorizedAmount().isEmpty()) {
                                    resp.setZnesekPOS(new BigDecimal(resp.getAuthorizedAmount().replace(",", ".")));
                                }
                            } catch (Exception ignored) {}

                            // Parsiranje morebitne napitnine
                            try {
                                if (!resp.getTipAmount().isEmpty()) {
                                    BigDecimal tip = new BigDecimal(resp.getTipAmount().replace(",", "."));
                                    if (tip.compareTo(BigDecimal.ZERO) >= 0 && tip.compareTo(new BigDecimal(1000)) <= 0) {
                                        resp.setpNapitnina(tip);
                                    }
                                }
                            } catch (Exception ignored) {}

                            // Formatiran bančni slip
                            StringBuilder sb = new StringBuilder();
                            addFormattedLine(sb, "Prodajalec", resp.getMerchant());
                            addFormattedLine(sb, "ID", resp.getMerchantId());
                            sb.append("--------------------------------------\n");
                            addFormattedLine(sb, "Terminal", resp.getTerminalId());
                            addFormattedLine(sb, "AID", resp.getApplicationIdentifier());
                            addFormattedLine(sb, "Kartica", resp.getApplicationLabel());
                            addFormattedLine(sb, "Št.", resp.getCardNumber());
                            addFormattedLine(sb, "Avt.koda", resp.getpAuthorizationCode());
                            addFormattedLine(sb, "Banka", resp.getBankName());
                            addFormattedLine(sb, "Datum", resp.getTimestamp());
                            sb.append("--------------------------------------\n");
                            addFormattedLine(sb, "Vrsta", resp.getOperationName());
                            addFormattedLine(sb, "Znesek", resp.getAuthorizedAmount());
                            sb.append("--------------------------------------\n");

                            resp.setReceipt(sb.toString());
                            resp.setClient(sb.toString());
                        }
                    } else {
                        resp.setResult("Napaka");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri razčlenjevanju PayTen odgovora: " + e.getMessage(), e);
            resp.setResult("Napaka");
            resp.setpFault("JSON parse napaka: " + e.getMessage());
        }

        return resp;
    }

    private static String optStringCI(JSONObject obj, String key) {
        if (obj == null || key == null) return "";
        if (obj.has(key)) return obj.optString(key, "");
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (key.equalsIgnoreCase(k)) {
                return obj.optString(k, "");
            }
        }
        return "";
    }

    private static JSONObject optObjectCI(JSONObject obj, String key) {
        if (obj == null || key == null) return null;
        if (obj.has(key)) return obj.optJSONObject(key);
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (key.equalsIgnoreCase(k)) {
                return obj.optJSONObject(k);
            }
        }
        return null;
    }

    private static void addFormattedLine(StringBuilder sb, String label, String value) {
        if (value == null) value = "";
        String line = String.format(Locale.getDefault(), "%-10s %s", label + ":", value);
        if (line.length() > 34) {
            line = line.substring(0, 34);
        }
        sb.append(line).append("\n");
    }
}
