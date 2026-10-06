package com.librego;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Único punto de envío de emails (recuperación de contraseña y facturas). Usa la configuración SMTP de Gmail. */
@Service
public class Mailer {
    private final JavaMailSender sender;
    @Value("${spring.mail.username:}") private String from;

    public Mailer(JavaMailSender sender) { this.sender = sender; }

    public boolean configured() { return from != null && !from.isBlank(); }

    /** Se ejecuta en segundo plano para no demorar la respuesta. attName/att son opcionales (adjunto). */
    @Async
    public void send(String to, String subject, String text, String html, String attName, byte[] att) {
        if (!configured()) {
            System.out.println("[Libre.go] (email NO configurado, no se envió) Para: " + to + " | Asunto: " + subject + "\n" + text);
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
            System.out.println("[Libre.go] No se pudo enviar el email a " + to + ": " + e.getMessage());
        }
    }
}
