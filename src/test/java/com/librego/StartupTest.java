package com.librego;

import com.librego.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Arranca TODO el contexto de Spring Boot con H2 en memoria: si hay un error de entidades JPA, consultas o repositorios
 * (por ejemplo "UnknownEntityException: Could not resolve root entity"), este test falla antes de llegar a producción.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:libregotest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.global-admin.password=clave-solo-para-tests-123",
        "mercadopago.access-token=",
        "mercadopago.webhook-secret=",
        "app.cors.allowed-origins="})
class StartupTest {
    @Autowired Companies companies;
    @Autowired Users users;
    @Autowired Recs recs;
    @Autowired Payments payments;

    @Test
    void elContextoArrancaYSeCreanLosRepositorios() {
        assertNotNull(companies);
        assertNotNull(users);
        assertNotNull(recs);
        assertNotNull(payments);
    }

    @Test
    void seCreaElAdministradorGlobalConLaContraseñaHasheada() {
        var u = users.findByUsername("lucas1");
        assertTrue(u.isPresent());
        assertEquals("global", u.get().role);
        assertNotEquals("clave-solo-para-tests-123", u.get().passHash);
    }
}
