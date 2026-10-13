package com.librego;

import com.librego.Models.Companies;
import com.librego.Models.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Las empresas creadas ANTES de existir la suscripción no tienen estado. Al arrancar se les asigna uno:
 *  - por defecto PENDIENTE_PAGO (tienen que pagar o el administrador global las activa a mano);
 *  - si definís SUBSCRIPTION_GRANT_EXISTING_DAYS=30 (por ejemplo), quedan ACTIVAS durante esa cantidad de días.
 */
@Component
public class SubscriptionMigration implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionMigration.class);
    private final Companies cos;
    @Value("${app.subscription.grandfather-days:0}") private long days;

    public SubscriptionMigration(Companies cos) { this.cos = cos; }

    @Override
    public void run(ApplicationArguments args) {
        int n = 0;
        Instant now = Instant.now();
        for (Company c : cos.findAll()) {
            if (c.subStatus != null) continue;
            if (days > 0) { c.subStatus = "ACTIVA"; c.subStart = now; c.subExpires = now.plus(days, ChronoUnit.DAYS); }
            else c.subStatus = "PENDIENTE_PAGO";
            cos.save(c);
            n++;
        }
        if (n > 0) LOG.info("Suscripción: {} empresa(s) existente(s) migrada(s) a {}.", n, days > 0 ? "ACTIVA por " + days + " días" : "PENDIENTE_PAGO");
    }
}
