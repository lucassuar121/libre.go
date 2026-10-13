package com.librego;

import com.librego.Models.AuditEvent;
import com.librego.Models.Audits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Auditoría de eventos de suscripción y bajas. Se guarda en una transacción propia: queda registrado aunque la operación original se revierta. */
@Service
public class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    private final Audits audits;

    public AuditService(Audits audits) { this.audits = audits; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long companyId, String companyName, String type, String detail) {
        AuditEvent a = new AuditEvent();
        a.at = Instant.now();
        a.companyId = companyId;
        a.companyName = companyName == null ? null : (companyName.length() > 200 ? companyName.substring(0, 200) : companyName);
        a.type = type;
        a.detail = detail == null ? "" : (detail.length() > 1900 ? detail.substring(0, 1900) : detail);
        audits.save(a);
        LOG.info("AUDITORÍA {} · empresa {} · {}", type, companyId, a.detail);
    }
}
