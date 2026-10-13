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

/** Único punto de envío de emails (recuperación de contraseña y facturas). Usa la configuración SMTP de Gmail. */
@Service
public class Mailer {
    private static final Logger LOG = LoggerFactory.getLogger(Mailer.class);
    private final JavaMailSender sender;
    @Value("${spring.mail.username:}") private String from;
    /** Solo para desarrollo: si el email no está configurado, muestra el contenido (por ejemplo el enlace de recuperación). En producción es false. */
    @Value("${app.mail.log-content:false}") private boolean logContent;

    public Mailer(JavaMailSender sender) { this.sender = sender; }

    public boolean configured() { return from != null && !from.isBlank(); }

    /** Se ejecuta en segundo plano para no demorar la respuesta. attName/att son opcionales (adjunto). */
    @Async
    public void send(String to, String subject, String text, String html, String attName, byte[] att) {
        if (!configured()) {
            if (logContent) LOG.info("(email NO configurado, no se envió) Para: {} | Asunto: {}\n{}", to, subject, text);
            else LOG.warn("Email no configurado: no se envió \"{}\".", subject);
            return;
        }
        try {
            MimeMessage m = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, true, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(text, html);
            if (att != null) h.addAttachment(attName, new ByteArrayResource(att));
            sender.send(m);
        } catch (Exception e) {
            LOG.warn("No se pudo enviar el email \"{}\": {}", subject, e.getMessage());
        }
    }
}
