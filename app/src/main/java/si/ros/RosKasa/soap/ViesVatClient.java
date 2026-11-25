package si.ros.RosKasa.soap;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Odjemalec za preverjanje EU DDV številk prek uradnega EU VIES REST API-ja
 * (ekvivalent Delphi Newlib/ViesVatClient.pas).
 */
public class ViesVatClient {

    public static class ViesResult {
        public boolean valid;
        public String countryCode = "";
        public String vatNumber = "";
        public String name = "";
        public String address = "";
        public String errorMessage = "";
    }

    /**
     * Preveri EU DDV številko (npr. "SI32355058", "DE123456789", "AT U12345678").
     */
    public static ViesResult checkVat(String fullVat) {
        ViesResult res = new ViesResult();
        if (fullVat == null) {
            res.errorMessage = "Prazna davčna številka.";
            return res;
        }
        String clean = fullVat.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-.]", "");
        if (clean.length() < 4 || !Character.isLetter(clean.charAt(0)) || !Character.isLetter(clean.charAt(1))) {
            res.errorMessage = "Davčna številka mora imeti 2-mestno EU oznako države (npr. SI, AT, DE, HR).";
            return res;
        }

        res.countryCode = clean.substring(0, 2);
        res.vatNumber = clean.substring(2);

        HttpURLConnection conn = null;
        try {
            String endpoint = "https://ec.europa.eu/taxation_customs/vies/rest-api/ms/"
                    + res.countryCode + "/vat/" + res.vatNumber;
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(sb.toString());
                res.valid = json.optBoolean("isValid", false);
                res.name = json.optString("name", "").trim();
                res.address = json.optString("address", "").trim().replace("\n", ", ");
                if ("---".equals(res.name)) res.name = "";
                if ("---".equals(res.address)) res.address = "";
                if (!res.valid) {
                    res.errorMessage = "EU DDV številka ni veljavna v VIES bazi.";
                }
            } else {
                res.errorMessage = "VIES strežnik vrnil HTTP " + code;
            }
        } catch (Exception e) {
            res.errorMessage = "Napaka pri povezavi z VIES: " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return res;
    }
}
