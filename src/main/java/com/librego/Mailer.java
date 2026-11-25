package com.librego;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Único punto de envío de emails (recuperación de contraseña y facturas).
 * Si existe BREVO_API_KEY se envía por la API HTTPS de Brevo (funciona en hosts que bloquean SMTP, como Render gratis).
 * Si no, se usa la configuración SMTP de Gmail (MAIL_USERNAME / MAIL_PASSWORD).
 */
@Service
public class Mailer {
    private static final Logger LOG = LoggerFactory.getLogger(Mailer.class);
    private final JavaMailSender sender;
    private final com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    @Value("${spring.mail.username:}") private String from;
    @Value("${BREVO_API_KEY:}") private String brevoKey;
    @Value("${MAIL_FROM:}") private String fromOverride;
    @Value("${app.mail.from-name:Libre.go}") private String fromName;
    /** Solo para desarrollo: si el email no está configurado, muestra el contenido. En producción es false. */
    @Value("${app.mail.log-content:false}") private boolean logContent;

    public Mailer(JavaMailSender sender) { this.sender = sender; }

    private boolean useApi() { return brevoKey != null && !brevoKey.isBlank(); }
    private String sender() { return fromOverride != null && !fromOverride.isBlank() ? fromOverride : from; }

    public boolean configured() { return useApi() ? !sender().isBlank() : from != null && !from.isBlank(); }

    /** Se ejecuta en segundo plano para no demorar la respuesta. attName/att son opcionales (adjunto). */
    @Async
    public void send(String to, String subject, String text, String html, String attName, byte[] att) {
        if (!configured()) {
            if (logContent) LOG.info("(email NO configurado, no se envió) Para: {} | Asunto: {}\n{}", to, subject, text);
            else LOG.warn("Email no configurado: no se envió \"{}\".", subject);
            return;
        }
        try {
            if (useApi()) sendApi(to, subject, text, html, attName, att); else sendSmtp(to, subject, text, html, attName, att);
            LOG.info("Email enviado: \"{}\"", subject);
        } catch (Exception e) {
            LOG.warn("No se pudo enviar el email \"{}\": {}", subject, e.getMessage());
        }
    }

    private void sendSmtp(String to, String subject, String text, String html, String attName, byte[] att) throws Exception {
        MimeMessage m = sender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(m, true, "UTF-8");
        h.setFrom(from);
        h.setTo(to);
        h.setSubject(subject);
        h.setText(text, html);
        if (att != null) h.addAttachment(attName, new ByteArrayResource(att));
        sender.send(m);
    }

    private void sendApi(String to, String subject, String text, String html, String attName, byte[] att) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sender", Map.of("name", fromName, "email", sender()));
        b.put("to", List.of(Map.of("email", to)));
        b.put("subject", subject);
        b.put("htmlContent", html);
        b.put("textContent", text);
        if (att != null) b.put("attachment", List.of(Map.of("name", attName, "content", Base64.getEncoder().encodeToString(att))));
        HttpRequest rq = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                .timeout(Duration.ofSeconds(20)).header("api-key", brevoKey).header("Content-Type", "application/json")
                .header("accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(b))).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new java.io.IOException("Brevo respondió " + r.statusCode() + ": " + r.body());
    }
}
