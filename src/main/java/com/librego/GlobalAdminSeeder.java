package com.librego;

import com.librego.Models.AppUser;
import com.librego.Models.Users;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea el administrador global una sola vez. La contraseña se guarda con BCrypt, igual que las demás. */
@Component
public class GlobalAdminSeeder implements ApplicationRunner {
    private final Users users;
    @Value("${app.global-admin.username}") private String username;
    @Value("${app.global-admin.password}") private String password;

    public GlobalAdminSeeder(Users users) { this.users = users; }

    @Override
    public void run(ApplicationArguments args) {
        String un = username.trim().toLowerCase();
        var existing = users.findByUsername(un);
        if (existing.isPresent()) {
            if (!"global".equals(existing.get().role))
                System.out.println("[Libre.go] ATENCIÓN: el usuario '" + un + "' ya existe y no es administrador global.");
            return;
        }
        AppUser u = new AppUser();
        u.name = "Administrador global";
        u.username = un;
        u.passHash = new BCryptPasswordEncoder().encode(password);
        u.role = "global";
        users.save(u);
        System.out.println("[Libre.go] Administrador global '" + un + "' creado.");
    }
}
