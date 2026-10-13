package com.librego;

import com.librego.Models.AppUser;
import com.librego.Models.Users;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea el administrador global una sola vez. La contraseña NO tiene valor por defecto: sale de la variable GLOBAL_ADMIN_PASSWORD
 * y se guarda con BCrypt. En producción es obligatoria (mínimo 12 caracteres) si el usuario todavía no existe.
 * Nunca se escribe en los logs.
 */
@Component
public class GlobalAdminSeeder implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalAdminSeeder.class);
    private final Users users;
    private final Environment env;
    @Value("${app.global-admin.username}") private String username;
    @Value("${app.global-admin.password:}") private String password;

    public GlobalAdminSeeder(Users users, Environment env) { this.users = users; this.env = env; }

    @Override
    public void run(ApplicationArguments args) {
        String un = username.trim().toLowerCase();
        var existing = users.findByUsername(un);
        if (existing.isPresent()) {
            if (!"global".equals(existing.get().role)) LOG.warn("El usuario '{}' ya existe y no es administrador global.", un);
            return;
        }
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        if (password == null || password.isBlank()) {
            if (prod) throw new IllegalStateException("[Libre.go] Falta la variable GLOBAL_ADMIN_PASSWORD: es obligatoria para crear el administrador global '" + un + "' en producción.");
            LOG.warn("No se creó el administrador global '{}': definí la variable GLOBAL_ADMIN_PASSWORD para crearlo.", un);
            return;
        }
        if (prod && password.length() < 12) throw new IllegalStateException("[Libre.go] GLOBAL_ADMIN_PASSWORD debe tener al menos 12 caracteres en producción.");
        AppUser u = new AppUser();
        u.name = "Administrador global";
        u.username = un;
        u.passHash = new BCryptPasswordEncoder().encode(password);
        u.role = "global";
        users.save(u);
        LOG.info("Administrador global '{}' creado.", un);
    }
}
