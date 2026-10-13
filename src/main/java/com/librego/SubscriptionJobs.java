package com.librego;

import com.librego.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tareas automáticas de suscripciones: revoca el acceso vencido y elimina las empresas dadas de baja cuyo período pagado terminó.
 * Funciona sin que nadie abra la aplicación: la ejecuta el propio servidor (@Scheduled) y, en Cloud Run, Cloud Scheduler llamando
 * a POST /api/internal/jobs/run con el encabezado X-Jobs-Token (porque Cloud Run puede reducir la CPU o apagar la instancia sin tráfico).
 */
@Service
public class SubscriptionJobs {
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionJobs.class);
    private final Companies cos;
    private final CompanyPurger purger;
    private final AuditService audit;
    private final MercadoPagoClient mp;
    @Value("${app.jobs.enabled:true}") private boolean enabled;
    @Value("${app.jobs.token:}") private String token;
    @Value("${app.subscription.delete-grace-days:0}") private long graceDays;

    public SubscriptionJobs(Companies cos, CompanyPurger purger, AuditService audit, MercadoPagoClient mp) {
        this.cos = cos; this.purger = purger; this.audit = audit; this.mp = mp;
    }

    public boolean tokenOk(String given) {
        if (token == null || token.isBlank() || given == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelayString = "${app.jobs.interval-ms:3600000}", initialDelayString = "${app.jobs.initial-delay-ms:60000}")
    public void scheduled() {
        if (!enabled) return;
        try { run(); } catch (RuntimeException e) { LOG.error("Falló la tarea automática de suscripciones", e); }
    }

    /** Idempotente y segura de repetir. Devuelve un resumen. */
    public synchronized Map<String, Object> run() {
        int expired = 0, deleted = 0, failed = 0, waiting = 0;
        Instant now = Instant.now();
        for (Company c : cos.findAll()) {
            try {
                if ("ACTIVA".equals(c.subStatus) && (c.subExpires == null || !now.isBefore(c.subExpires)) && purger.markExpired(c.id)) expired++;
                if (c.subCancelRequestedAt != null && c.subCancelConfirmedAt != null && c.subExpires != null
                        && !now.isBefore(c.subExpires.plusSeconds(graceDays * 86400L))) {
                    // antes de borrar se vuelve a comprobar con Mercado Pago que la suscripción esté realmente cancelada
                    if (c.mpPreapprovalId != null) {
                        if (!mp.configured()) { LOG.warn("No se elimina '{}': Mercado Pago no está configurado para verificar la cancelación.", c.name); waiting++; continue; }
                        Map<String, Object> pre = mp.getPreapproval(c.mpPreapprovalId);
                        if (pre != null && !"cancelled".equals(String.valueOf(pre.get("status")))) {
                            LOG.warn("No se elimina '{}': su suscripción en Mercado Pago figura '{}'.", c.name, pre.get("status")); waiting++; continue;
                        }
                    }
                    if (purger.purgeIfDue(c.id, graceDays)) deleted++;
                }
            } catch (Exception e) {
                failed++;
                LOG.error("Falló el procesamiento de la empresa {}: {}", c.id, e.toString());
                try { audit.record(c.id, c.name, "JOB_FAILED", String.valueOf(e.getMessage())); } catch (RuntimeException ignored) { /* se reintenta en la próxima ejecución */ }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("expired", expired); r.put("deleted", deleted); r.put("failed", failed); r.put("waiting", waiting);
        if (expired + deleted + failed > 0) LOG.info("Tarea de suscripciones: {}", r);
        return r;
    }
}
