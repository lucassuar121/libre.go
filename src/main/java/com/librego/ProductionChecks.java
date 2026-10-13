package com.librego;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Controla la configuración al arrancar. Con el perfil "prod" la aplicación NO inicia si falta algo crítico
 * y el mensaje dice exactamente qué falta. Nunca muestra valores secretos: solo si están o no configurados.
 */
@Component
public class ProductionChecks {
    private static final Logger LOG = LoggerFactory.getLogger(ProductionChecks.class);
    private final Environment env;
    @Value("${app.base-url}") private String baseUrl;
    @Value("${spring.datasource.url:}") private String dbUrl;
    @Value("${mercadopago.access-token:}") private String mpToken;
    @Value("${mercadopago.webhook-url:}") private String mpHook;
    @Value("${mercadopago.webhook-secret:}") private String mpSecret;
    @Value("${app.jobs.token:}") private String jobsToken;

    public ProductionChecks(Environment env) { this.env = env; }

    @PostConstruct
    void check() {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        if (!prod) {
            if (System.getenv("K_SERVICE") != null)
                throw new IllegalStateException("[Libre.go] Se detectó Google Cloud Run pero el perfil 'prod' no está activo. Definí SPRING_PROFILES_ACTIVE=prod (el Dockerfile ya lo hace).");
            return;
        }
        List<String> errs = new ArrayList<>();
        if (!baseUrl.startsWith("https://") || baseUrl.contains("localhost"))
            errs.add("BASE_URL tiene que ser la URL pública de la app con https:// (por ejemplo https://app.tudominio.com)");
        if (dbUrl.contains(":h2:"))
            errs.add("DB_URL apunta a H2: en producción hay que usar PostgreSQL (los archivos locales se pierden en Cloud Run)");
        boolean mp = !mpToken.isBlank();
        if (mp && mpHook.isBlank()) errs.add("Falta MERCADOPAGO_WEBHOOK_URL: la URL pública del webhook (https://TU-DOMINIO/api/mercadopago/webhook) que hay que cargar también en el panel de Mercado Pago; sin ese aviso no se activa ninguna suscripción");
        if (mp && !mpHook.isBlank() && !mpHook.startsWith("https://")) errs.add("MERCADOPAGO_WEBHOOK_URL tiene que empezar con https://");
        if (mp && mpSecret.isBlank()) errs.add("Falta MERCADOPAGO_WEBHOOK_SECRET: configurá el secreto de firma del webhook antes de habilitar cobros reales.");
        if (!errs.isEmpty()) throw new IllegalStateException("[Libre.go] Configuración de producción incompleta:\n - " + String.join("\n - ", errs));
        if (jobsToken == null || jobsToken.isBlank()) LOG.warn("Falta JOBS_TOKEN: Cloud Scheduler no podrá ejecutar las tareas automáticas (solo corre la tarea interna del servidor, que en Cloud Run puede no ejecutarse sin tráfico).");
        if (!mp) LOG.warn("Mercado Pago NO está configurado (falta MERCADOPAGO_ACCESS_TOKEN): los pagos online están deshabilitados.");

    }
}
