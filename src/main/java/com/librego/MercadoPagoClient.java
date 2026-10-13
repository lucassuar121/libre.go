package com.librego;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Cliente mínimo de la API REST de Mercado Pago (Checkout Pro). No usa SDK.
 *
 * >>> AQUÍ SE CONFIGURA MERCADO PAGO: todo se lee de variables de entorno (ver application.properties y .env.example).
 *     MERCADOPAGO_ACCESS_TOKEN, MERCADOPAGO_PUBLIC_KEY, MERCADOPAGO_WEBHOOK_URL, MERCADOPAGO_WEBHOOK_SECRET, MERCADOPAGO_SANDBOX
 * Nada de esto está escrito en el código.
 */
@Service
public class MercadoPagoClient {
    private static final String API = "https://api.mercadopago.com";

    private final ObjectMapper om;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Value("${mercadopago.access-token:}") private String accessToken;
    @Value("${mercadopago.public-key:}") private String publicKey; // no se usa con Checkout Pro por redirección; queda disponible
    @Value("${mercadopago.webhook-url:}") private String webhookUrl;
    @Value("${mercadopago.webhook-secret:}") private String webhookSecret;
    @Value("${mercadopago.sandbox:false}") private boolean sandbox;

    public MercadoPagoClient(ObjectMapper om) { this.om = om; }

    public boolean configured() { return accessToken != null && !accessToken.isBlank(); }
    public String webhookUrl() { return webhookUrl == null ? "" : webhookUrl.trim(); }
    public String publicKey() { return publicKey == null ? "" : publicKey; }

    /** Crea una suscripción con renovación automática (preapproval). Devuelve id, status, init_point, ... */
    public Map<String, Object> createPreapproval(Map<String, Object> body) throws Exception {
        return call("POST", "/preapproval", body);
    }

    /** Estado real de una suscripción en Mercado Pago. null si no existe. */
    public Map<String, Object> getPreapproval(String id) throws Exception {
        return call("GET", "/preapproval/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8), null);
    }

    /** Cancelación OFICIAL de la suscripción: PUT /preapproval/{id} con status "cancelled". Después de esto Mercado Pago no genera más cobros. */
    public Map<String, Object> cancelPreapproval(String id) throws Exception {
        return call("PUT", "/preapproval/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8), Map.of("status", "cancelled"));
    }

    /** Un cobro de una suscripción (incluye preapproval_id y el pago asociado). null si no existe. */
    public Map<String, Object> getAuthorizedPayment(String id) throws Exception {
        return call("GET", "/authorized_payments/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8), null);
    }

    /** Consulta un pago directamente a Mercado Pago (fuente de verdad). Devuelve null si el id no existe. */
    public Map<String, Object> getPayment(String id) throws Exception {
        return call("GET", "/v1/payments/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8), null);
    }

    /** URL a la que se redirige al usuario para pagar. En modo sandbox usa la URL de prueba si Mercado Pago la devuelve. */
    public String checkoutUrl(Map<String, Object> pref) {
        String sb = String.valueOf(pref.getOrDefault("sandbox_init_point", "")), ip = String.valueOf(pref.getOrDefault("init_point", ""));
        if (sandbox && !sb.isBlank() && !"null".equals(sb)) return sb;
        return "null".equals(ip) ? "" : ip;
    }

    private Map<String, Object> call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken).header("Content-Type", "application/json");
        HttpRequest req = body == null ? b.GET().build()
                : b.method(method, HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404 && method.equals("GET")) return null;
        if (r.statusCode() / 100 != 2) {
            String t = r.body() == null ? "" : r.body();
            throw new IllegalStateException("Mercado Pago respondió " + r.statusCode() + ": " + (t.length() > 300 ? t.substring(0, 300) : t));
        }
        return om.readValue(r.body(), new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Valida la firma (x-signature) de las notificaciones con el "secreto de firma" del panel de Mercado Pago.
     * Si MERCADOPAGO_WEBHOOK_SECRET no está configurado devuelve true: en ese caso la seguridad descansa en que el pago
     * SIEMPRE se vuelve a consultar a Mercado Pago con el access token antes de activar nada (ver Api.processMpPayment).
     */
    public boolean validSignature(String xSignature, String xRequestId, String dataId) {
        if (webhookSecret == null || webhookSecret.isBlank()) return true;
        if (xSignature == null) return false;
        String ts = null, v1 = null;
        for (String part : xSignature.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("ts")) ts = kv[1];
            if (kv[0].equals("v1")) v1 = kv[1];
        }
        if (ts == null || v1 == null) return false;
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isEmpty()) manifest.append("id:").append(dataId.toLowerCase()).append(';');
        if (xRequestId != null && !xRequestId.isEmpty()) manifest.append("request-id:").append(xRequestId).append(';');
        manifest.append("ts:").append(ts).append(';');
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String calc = HexFormat.of().formatHex(mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(calc.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return false; }
    }
}
