package si.ros.RosKasa.payment;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import si.ros.RosKasa.Globals;
import si.ros.RosKasa.payment.models.SixTapResponse;

public class SixTapPaymentService {

    private static final String TAG = "SixTapPaymentService";
    public static final int REQUEST_CODE_SIXTAP = 0;

    public static final String ACTION_PROCESS_TRANSACTION = "com.worldline.payment.action.PROCESS_TRANSACTION";
    public static final String OP_PAYMENT = "WPI_SVC_PAYMENT";
    public static final String OP_LAST_TRANSACTION = "WPI_SVC_LAST_TRANSACTION";
    public static final String OP_REFUND = "WPI_SVC_REFUND";
    public static final String OP_CANCEL_PAYMENT = "WPI_SVC_CANCEL_PAYMENT";

    public interface SixTapLaunchCallback {
        void onProceedLaunch();
        void onRecoverConfirmed(SixTapResponse confirmedResp);
        void onAutoCheckLastTransaction(String sessionId, int pendingRacunId);
        void onBlocked(String message);
    }

    /**
     * Blokada in 3-stopenjska eskalacija ob poskusu novega SixTap plačila z nerešeno transakcijo
     * (skladno z Delphi uSixTapPayment.pas: 1=opozorilo, 2=auto LAST_TRANSACTION, 3=prisilni reset).
     */
    public static void prepareAndCheckPayment(Context context, int racunId, SixTapLaunchCallback callback) {
        if (callback == null) return;
        Globals g = Globals.getInstance();

        // 1. Preveri, ali že obstaja na POS potrjena transakcija za ta račun
        SixTapResponse confirmedResp = new SixTapResponse();
        int[] confirmedState = new int[1];
        if (PaymentRecoveryManager.tryGetConfirmedPOSTransaction(context, confirmedResp, confirmedState)) {
            if (confirmedState[0] >= PaymentRecoveryManager.POS_STATE_POS_CONFIRMED
                    && confirmedResp.getpRacunId() == racunId) {
                g.vpisiKronologijo("SIXTAP BLOKADA NOVO PLACILO: R:" + racunId + " že potrjeno na POS (State:" + confirmedState[0] + "), zaključujem recovery.");
                callback.onRecoverConfirmed(confirmedResp);
                return;
            }
        }

        // 2. Preveri nedokončano (recovery ali pending) SixTap transakcijo
        PaymentRecoveryManager.PendingPaymentInfo info = PaymentRecoveryManager.tryGetRecoveryPayment(context);
        if (!info.isValid()) {
            info = PaymentRecoveryManager.tryGetPendingPayment(context);
        }

        if (info.isValid()) {
            String pendingSessionId = info.getEffectiveSessionId();
            int pendingRacunId = 0;
            try {
                pendingRacunId = Integer.parseInt(info.getEffectiveRacunId());
            } catch (Exception ignored) {}

            int retry = PaymentRecoveryManager.incrementRecoveryRetryCount(context);
            if (retry == 1) {
                g.vpisiKronologijo("SIXTAP ESKALACIJA 1 (OPOZORILO) R:" + racunId + " PendingR:" + pendingRacunId + " Session:" + pendingSessionId);
                callback.onBlocked("Obstaja nedokončana Worldline Tap On transakcija za račun #" + pendingRacunId +
                        ".\n\nUporabite tipko 'Tap On' za preverjanje statusa ali poskusite še enkrat za samodejno preverjanje.");
                return;
            } else if (retry == 2) {
                g.vpisiKronologijo("SIXTAP ESKALACIJA 2 (AUTO LAST_TRANSACTION) R:" + racunId + " PendingR:" + pendingRacunId + " Session:" + pendingSessionId);
                callback.onAutoCheckLastTransaction(pendingSessionId, pendingRacunId);
                return;
            } else {
                g.vpisiKronologijo("SIXTAP ESKALACIJA 3 (PRISILNI RESET) R:" + racunId + " PendingR:" + pendingRacunId + " Session:" + pendingSessionId);
                PaymentRecoveryManager.clearPendingPayment(context);
                PaymentRecoveryManager.clearRecoveryPayment(context);
                PaymentRecoveryManager.clearConfirmedPOSTransaction(context);
                g.setSixtapintransaction(false);
            }
        }

        callback.onProceedLaunch();
    }

    public static Intent buildPaymentIntent(Context context, int racunId, int pozicijaId, BigDecimal amount,
                                            BigDecimal tipAmount, String operation, String mRef, String existingSessionId) {
        Globals g = Globals.getInstance();
        String op = (operation != null && !operation.isEmpty()) ? operation : OP_PAYMENT;
        String wpiVersion = g.getSixTapWpiVersion();
        if (wpiVersion == null || wpiVersion.isEmpty()) {
            wpiVersion = "2.2";
        }
        String format = g.getSixTapFormat();

        // Generiranje ali uporaba obstoječega Session ID (UUID brez vezajev)
        String sessionId;
        if (OP_LAST_TRANSACTION.equals(op) && existingSessionId != null && !existingSessionId.isEmpty()) {
            sessionId = existingSessionId;
        } else if (existingSessionId != null && !existingSessionId.isEmpty()) {
            sessionId = existingSessionId;
        } else {
            sessionId = UUID.randomUUID().toString().replace("-", "");
        }

        int amountCents = amount != null ? amount.multiply(new BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).intValue() : 0;
        int tipCents = tipAmount != null ? tipAmount.multiply(new BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).intValue() : 0;

        Intent intent = new Intent(ACTION_PROCESS_TRANSACTION);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        intent.putExtra("WPI_SERVICE_TYPE", op);
        intent.putExtra("WPI_VERSION", wpiVersion);
        intent.putExtra("WPI_SESSION_ID", sessionId);

        if (!OP_LAST_TRANSACTION.equals(op)) {
            String jsonRequest = buildWpiJsonRequest(amountCents, tipCents, racunId, mRef, format, op);
            Log.i(TAG, "SixTap WPI Zahteva za R:" + racunId + " (" + op + "): " + jsonRequest);
            intent.putExtra("WPI_REQUEST", jsonRequest);
            Globals.getInstance().vpisiKronologijo("SIXTAP R:" + racunId + " SubscribeToMessage " + op + " WPI_REQUEST: " + jsonRequest + " WPI_VERSION: " + wpiVersion + " WPI_SESSION_ID: " + sessionId + " F: " + (format != null ? format : ""));
        } else {
            Log.i(TAG, "SixTap WPI LAST_TRANSACTION Zahteva za R:" + racunId + " sessionId:" + sessionId);
            Globals.getInstance().vpisiKronologijo("SIXTAP R:" + racunId + " SubscribeToMessage " + op + " WPI_VERSION: " + wpiVersion + " WPI_SESSION_ID: " + sessionId + " F: " + (format != null ? format : ""));
        }

        // Hramba začasnih podatkov za okrevanje (če ni LAST_TRANSACTION)
        if (!OP_LAST_TRANSACTION.equals(op)) {
            PaymentRecoveryManager.clearPendingPayment(context);
            PaymentRecoveryManager.savePendingPayment(context, "", "", "", sessionId, String.valueOf(racunId), String.valueOf(amountCents));
            PaymentRecoveryManager.setPOSTransactionState(context, PaymentRecoveryManager.POS_STATE_PENDING_POS);
        }

        g.setSixtapintransaction(true);
        g.setZadnjiWpiSessionId(sessionId);
        g.setZadnjiSixRacunId(racunId);
        g.setZadnjaSixPozicijaId(pozicijaId);

        return intent;
    }

    private static String optStringCI(JSONObject obj, String key, String defVal) {
        if (obj == null || key == null) return defVal;
        if (obj.has(key)) return obj.optString(key, defVal);
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (key.equalsIgnoreCase(k)) {
                return obj.optString(k, defVal);
            }
        }
        return defVal;
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

    private static String buildWpiJsonRequest(int amountCents, int tipCents, int racunId, String mRef, String format, String op) {
        try {
            JSONObject json = new JSONObject();
            if (OP_CANCEL_PAYMENT.equals(op) && mRef != null && !mRef.isEmpty()) {
                json.put("paymentSolutionReference", mRef);
                json.put("reference", String.valueOf(racunId));
            } else {
                json.put("currency", "EUR");
                json.put("requestedAmount", amountCents);
                if (tipCents > 0) {
                    json.put("tipAmount", tipCents);
                }
                if (OP_REFUND.equals(op) && mRef != null && !mRef.isEmpty()) {
                    json.put("paymentSolutionReference", mRef);
                }
                json.put("reference", String.valueOf(racunId));

                if (format != null && !format.isEmpty()) {
                    JSONArray arr = new JSONArray();
                    arr.put(format);
                    json.put("receiptFormat", arr);
                }
            }
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Napaka pri sestavi WPI JSON: " + e.getMessage(), e);
            return "{}";
        }
    }

    public static SixTapResponse parseResponseIntent(Intent data) {
        SixTapResponse resp = new SixTapResponse();
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

        CharSequence sessionIdSeq = extras.getCharSequence("WPI_SESSION_ID");
        if (sessionIdSeq != null) {
            resp.setpWpiSessionId(sessionIdSeq.toString());
        }

        CharSequence responseSeq = extras.getCharSequence("WPI_RESPONSE");
        if (responseSeq == null) {
            resp.setResult("Napaka");
            resp.setpFault("WPI_RESPONSE ni vsebovan v Extras");
            return resp;
        }

        String jsonString = responseSeq.toString();
        Log.i(TAG, "SixTap Odgovor JSON: " + jsonString);

        try {
            JSONObject root = new JSONObject(jsonString);
            resp.setResult(optStringCI(root, "result", ""));
            resp.setErrorCondition(optStringCI(root, "errorCondition", ""));
            resp.setRemark(optStringCI(root, "remark", ""));
            resp.setTimestamp(optStringCI(root, "timestamp", ""));
            resp.setCurrency(optStringCI(root, "currency", "EUR"));
            resp.setAuthorizedAmount(optStringCI(root, "authorizedAmount", ""));
            resp.setTipAmount(optStringCI(root, "tipAmount", ""));
            resp.setBrandName(optStringCI(root, "brandName", "").replace("WPI_BRAND_NAME_", ""));
            resp.setCustomerLanguage(optStringCI(root, "customerLanguage", ""));
            resp.setApplicationIdentifier(optStringCI(root, "applicationIdentifier", ""));
            resp.setApplicationLabel(optStringCI(root, "applicationLabel", ""));
            resp.setPaymentSolutionReference(optStringCI(root, "paymentSolutionReference", ""));
            resp.setReference(optStringCI(root, "reference", ""));

            // 1. Luščenje client in merchant iz strukture receipt / formatted (case-insensitive)
            String clientStr = "";
            String merchantStr = "";

            JSONObject receiptObj = optObjectCI(root, "receipt");
            if (receiptObj != null) {
                JSONObject formattedObj = optObjectCI(receiptObj, "formatted");
                if (formattedObj != null) {
                    clientStr = optStringCI(formattedObj, "client", "");
                    merchantStr = optStringCI(formattedObj, "merchant", "");
                } else {
                    clientStr = optStringCI(receiptObj, "client", "");
                    merchantStr = optStringCI(receiptObj, "merchant", "");
                }
            } else {
                String receiptRaw = optStringCI(root, "receipt", "");
                if (receiptRaw.startsWith("{")) {
                    try {
                        JSONObject parsedReceipt = new JSONObject(receiptRaw);
                        JSONObject formattedObj = optObjectCI(parsedReceipt, "formatted");
                        if (formattedObj != null) {
                            clientStr = optStringCI(formattedObj, "client", "");
                            merchantStr = optStringCI(formattedObj, "merchant", "");
                        } else {
                            clientStr = optStringCI(parsedReceipt, "client", "");
                            merchantStr = optStringCI(parsedReceipt, "merchant", "");
                        }
                    } catch (Exception ignored) {}
                } else if (!receiptRaw.isEmpty()) {
                    clientStr = receiptRaw;
                }
            }

            if (clientStr.isEmpty()) {
                JSONObject formattedObj = optObjectCI(root, "formatted");
                if (formattedObj != null) {
                    clientStr = optStringCI(formattedObj, "client", "");
                    merchantStr = optStringCI(formattedObj, "merchant", "");
                }
            }

            if (clientStr.isEmpty()) {
                clientStr = optStringCI(root, "client", "");
            }
            if (merchantStr.isEmpty()) {
                merchantStr = optStringCI(root, "merchant", "");
            }

            if (merchantStr.isEmpty()) {
                merchantStr = clientStr;
            }
            if (clientStr.isEmpty()) {
                clientStr = merchantStr;
            }

            // Čiščenje kontrolnih znakov in Delphi specifičnih nizov
            clientStr = cleanSlipText(clientStr);
            merchantStr = cleanSlipText(merchantStr);

            resp.setClient(clientStr);
            resp.setMerchant(merchantStr);
            resp.setReceipt(clientStr);

            // Delphi luščenje ARQC (acqreference)
            int arqcPos = clientStr.indexOf("ARQC:");
            if (arqcPos >= 0) {
                int startPos = arqcPos + "ARQC:".length();
                int endPos = clientStr.indexOf('\n', startPos);
                if (endPos < 0) endPos = clientStr.indexOf('\r', startPos);
                if (endPos > startPos) {
                    resp.setAcqreference(clientStr.substring(startPos, endPos).trim());
                } else if (startPos < clientStr.length()) {
                    resp.setAcqreference(clientStr.substring(startPos).trim());
                }
            }

            // Luščenje Authorization code (authNumber)
            int authPos = clientStr.indexOf("Authorization code:");
            if (authPos >= 0) {
                int startPos = authPos + "Authorization code:".length();
                int endPos = clientStr.indexOf('\n', startPos);
                if (endPos < 0) endPos = clientStr.indexOf('\r', startPos);
                String code = (endPos > startPos) ? clientStr.substring(startPos, endPos).trim() : clientStr.substring(startPos).trim();
                if (code.contains(")")) {
                    code = code.substring(code.lastIndexOf(")") + 1).trim();
                }
                resp.setAuthNumber(code);
            }

            // Luščenje AID (applicationIdentifier) če ni v JSON
            int aidPos = clientStr.indexOf("AID:");
            if (aidPos >= 0 && resp.getApplicationIdentifier().isEmpty()) {
                int startPos = aidPos + "AID:".length();
                int endPos = clientStr.indexOf('\n', startPos);
                if (endPos < 0) endPos = clientStr.indexOf('\r', startPos);
                if (endPos > startPos) {
                    resp.setApplicationIdentifier(clientStr.substring(startPos, endPos).trim());
                }
            }

            // Luščenje maskirane številke kartice (npr. "Mastercard 543661******0033")
            int starPos = clientStr.indexOf("**** ");
            if (starPos < 0) {
                starPos = clientStr.indexOf("****");
            }
            if (starPos >= 0) {
                int i = starPos;
                while (i > 0 && clientStr.charAt(i - 1) != '\n' && clientStr.charAt(i - 1) != '\r') {
                    i--;
                }
                int startPos = i;
                int endPos = clientStr.indexOf('\n', starPos);
                if (endPos < 0) {
                    endPos = clientStr.indexOf('\r', starPos);
                }
                String rawCardLine = (endPos > startPos)
                        ? clientStr.substring(startPos, endPos).trim()
                        : ((startPos < clientStr.length()) ? clientStr.substring(startPos).trim() : "");
                resp.setCardnumber(cleanCardNumber(rawCardLine));
            }

            // Preračun zneska POS (centov v EUR)
            if (!resp.getAuthorizedAmount().isEmpty()) {
                try {
                    BigDecimal cents = new BigDecimal(resp.getAuthorizedAmount());
                    resp.setZnesekPOS(cents.divide(new BigDecimal(100), 2, RoundingMode.HALF_UP));
                } catch (Exception ignored) {}
            }

            // Preračun morebitne napitnine
            if (!resp.getTipAmount().isEmpty()) {
                try {
                    BigDecimal tipCents = new BigDecimal(resp.getTipAmount());
                    BigDecimal tipEur = tipCents.divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
                    if (tipEur.compareTo(BigDecimal.ZERO) >= 0 && tipEur.compareTo(new BigDecimal(1000)) <= 0) {
                        resp.setpNapitnina(tipEur);
                    }
                } catch (Exception ignored) {}
            }

            try {
                if (!resp.getReference().isEmpty()) {
                    resp.setpRacunId(Integer.parseInt(resp.getReference()));
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Log.e(TAG, "Napaka pri razčlenjevanju WPI JSON odgovora: " + e.getMessage(), e);
            resp.setResult("Napaka");
            resp.setpFault("JSON parse napaka: " + e.getMessage());
        }

        return resp;
    }

    public static String cleanSlipText(String rawSlip) {
        if (rawSlip == null || rawSlip.trim().isEmpty()) return "";
        String s = rawSlip.trim();
        if (s.startsWith("{") && (s.contains("\"client\"") || s.contains("\"formatted\"") || s.contains("\"merchant\""))) {
            try {
                JSONObject obj = new JSONObject(s);
                JSONObject formatted = obj.optJSONObject("formatted");
                if (formatted != null) {
                    String c = formatted.optString("client", "");
                    if (c.isEmpty()) c = formatted.optString("merchant", "");
                    if (!c.isEmpty()) s = c;
                } else {
                    String c = obj.optString("client", "");
                    if (c.isEmpty()) c = obj.optString("merchant", "");
                    if (!c.isEmpty()) s = c;
                }
            } catch (Exception ignored) {}
        }
        s = s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n");
        s = s.replace("This is to confirm your transaction", "")
             .replace("registered at:", "Transaction :")
             .replace("----------------------------------------", "------------------------------")
             .replace("              ", "");
        return s.trim();
    }

    public static String cleanCardNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        // Če vsebuje predpono ključa npr. "STEVILKA_KARTICE":"Mastercard..." ali "cardNumber":"..."
        if (s.contains(":")) {
            s = s.substring(s.lastIndexOf(":") + 1).trim();
        }
        // Odstrani vse narekovaje
        s = s.replace("\"", "").replace("'", "").trim();
        // Odstrani ločila na koncu (, ; { })
        s = s.replaceAll("[,;{}]+$", "").trim();
        return s;
    }

    public static String extractDigitsOnly(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = cleanCardNumber(raw);
        int firstDigitOrStar = -1;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (Character.isDigit(ch) || ch == '*') {
                firstDigitOrStar = k;
                break;
            }
        }
        if (firstDigitOrStar >= 0) {
            s = s.substring(firstDigitOrStar).trim();
        }
        int spaceIdx = s.indexOf(' ');
        if (spaceIdx > 0 && !s.contains("**** ****")) {
            String firstPart = s.substring(0, spaceIdx).trim();
            if (firstPart.contains("*") || firstPart.matches("\\d{12,19}")) {
                s = firstPart;
            }
        }
        return s;
    }
}
