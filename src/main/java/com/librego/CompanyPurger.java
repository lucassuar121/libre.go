package com.librego;

import com.librego.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Operaciones por empresa en una transacción propia (REQUIRES_NEW): si una falla, las demás empresas no se ven afectadas y se puede
 * reintentar. Cada una bloquea la fila de la empresa y vuelve a comprobar las condiciones antes de actuar (seguro con varias instancias).
 */
@Service
public class CompanyPurger {
    private static final Logger LOG = LoggerFactory.getLogger(CompanyPurger.class);
    private final Companies cos;
    private final Users users;
    private final Recs recs;
    private final Audits audits;

    public CompanyPurger(Companies cos, Users users, Recs recs, Audits audits) {
        this.cos = cos; this.users = users; this.recs = recs; this.audits = audits;
    }

    private void event(Company c, String type, String detail) {
        AuditEvent a = new AuditEvent();
        a.at = Instant.now(); a.companyId = c.id; a.companyName = c.name; a.type = type; a.detail = detail;
        audits.save(a);
    }

    /** Pasa a VENCIDA una suscripción ACTIVA cuyo vencimiento ya pasó. (El acceso ya se corta en cada pedido; esto deja el estado guardado.) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markExpired(Long id) {
        Company c = cos.findWithLockById(id).orElse(null);
        if (c == null || Boolean.TRUE.equals(c.demo) || !"ACTIVA".equals(c.subStatus)) return false;
        if (c.subExpires != null && Instant.now().isBefore(c.subExpires)) return false;
        c.subStatus = "VENCIDA";
        cos.save(c);
        event(c, "ACCESS_REVOKED", "Venció el período pagado (" + c.subExpires + "): se revocó el acceso.");
        return true;
    }

    /**
     * Elimina la empresa y sus datos SOLO si pidió la baja, Mercado Pago confirmó la cancelación y ya terminó el último período pagado
     * (más los días de gracia configurados). Conserva el historial de pagos (registros contables) y la auditoría.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purgeIfDue(Long id, long graceDays) {
        Company c = cos.findWithLockById(id).orElse(null);
        if (c == null || Boolean.TRUE.equals(c.demo)) return false; // ya eliminada o empresa demo protegida
        Instant now = Instant.now();
        if (c.subCancelRequestedAt == null || c.subCancelConfirmedAt == null || c.subExpires == null) return false;
        if (now.isBefore(c.subExpires.plusSeconds(graceDays * 86400L))) return false;
        List<AppUser> us = users.findByCompanyId(id);
        if (us.stream().anyMatch(x -> "global".equals(x.role))) return false;
        recs.deleteByCompanyId(id);
        users.deleteAll(us);
        cos.delete(c);
        event(c, "COMPANY_DELETED", "Eliminación automática: baja solicitada el " + c.subCancelRequestedAt + " y período pagado vencido el " + c.subExpires + ". Se eliminaron " + us.size() + " usuario(s) y todos los datos de la empresa; se conservan los pagos.");
        LOG.info("Empresa {} (id {}) eliminada tras el vencimiento del período pagado.", c.name, id);
        return true;
    }
}
