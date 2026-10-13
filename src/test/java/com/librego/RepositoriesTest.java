package com.librego;

import com.librego.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Ejecuta de verdad cada consulta de los repositorios (incluido el bloqueo de fila) contra H2. Cada test se revierte al terminar. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:libregotest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.global-admin.password=clave-solo-para-tests-123",
        "mercadopago.access-token=",
        "mercadopago.webhook-secret=",
        "app.cors.allowed-origins="})
@Transactional
class RepositoriesTest {
    @Autowired Companies companies;
    @Autowired Users users;
    @Autowired Recs recs;
    @Autowired Payments payments;
    @Autowired Audits audits;

    @Test
    void todasLasConsultasFuncionan() {
        Company c = new Company();
        c.name = "Empresa de prueba";
        c.subStatus = "PENDIENTE_PAGO";
        c.mpPreapprovalId = "pre-1";
        c.mpSubRef = "ref-sub-1";
        c = companies.save(c);
        assertTrue(companies.findWithLockById(c.id).isPresent()); // SELECT ... FOR UPDATE
        assertEquals(c.id, companies.findProjectedByMpPreapprovalId("pre-1").get().getId());
        assertEquals(c.id, companies.findProjectedByMpSubRef("ref-sub-1").get().getId());
        AuditEvent a = new AuditEvent();
        a.at = Instant.now(); a.companyId = c.id; a.companyName = "Empresa de prueba"; a.type = "TEST"; a.detail = "detalle";
        audits.save(a);
        assertFalse(audits.findTop100ByOrderByIdDesc().isEmpty());

        Payment p = new Payment();
        p.companyId = c.id; p.reference = "ref-1"; p.mpPaymentId = "mp-1"; p.source = "MERCADOPAGO"; p.status = "PENDING";
        p.amount = new BigDecimal("30000"); p.currency = "ARS"; p.createdAt = Instant.now();
        payments.save(p);
        assertTrue(payments.findByReference("ref-1").isPresent());
        assertEquals(c.id, payments.findProjectedByReference("ref-1").get().getCompanyId());
        assertTrue(payments.findByMpPaymentId("mp-1").isPresent());
        assertEquals(1, payments.findByCompanyIdOrderByCreatedAtDesc(c.id).size());

        Rec r = new Rec();
        r.companyId = c.id; r.kind = "log"; r.payload = "{}";
        recs.save(r);
        assertEquals(1, recs.findByCompanyIdAndKind(c.id, "log").size());
        assertEquals(1, recs.findTop300ByCompanyIdAndKindOrderByIdDesc(c.id, "log").size());

        AppUser u = new AppUser();
        u.companyId = c.id; u.name = "Jefe"; u.username = "repo-jefe"; u.email = "Repo@Test.com"; u.passHash = "x"; u.role = "jefe"; u.resetToken = "tok";
        users.save(u);
        assertTrue(users.findByUsername("repo-jefe").isPresent());
        assertTrue(users.findByEmailIgnoreCase("repo@test.com").isPresent());
        assertTrue(users.findByResetToken("tok").isPresent());
        assertEquals(1, users.findByCompanyId(c.id).size());

        recs.deleteByCompanyId(c.id);
        assertTrue(recs.findByCompanyIdAndKind(c.id, "log").isEmpty());
    }
}
