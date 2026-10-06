package com.librego;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Entidades y repositorios. Rec guarda los datos de cada empresa (productos, ventas, turnos...) como JSON. */
public class Models {

    @Entity public static class Company {
        @Id @GeneratedValue public Long id;
        public String name;
    }

    @Entity public static class AppUser {
        @Id @GeneratedValue public Long id;
        public Long companyId;
        public String name;
        @Column(unique = true) public String username;
        public String email;
        public String passHash;
        public String role; // "jefe" | "empleado"
        public String resetToken;
        public Instant resetExp;
    }

    @Entity public static class Rec {
        @Id @GeneratedValue public Long id;
        public Long companyId;
        public String kind; // products, clients, suppliers, sales, moves, shifts, log
        @Column(length = 100000) public String payload;
    }

    public interface Companies extends JpaRepository<Company, Long> {}

    public interface Users extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByUsername(String username);
        Optional<AppUser> findByEmailIgnoreCase(String email);
        Optional<AppUser> findByResetToken(String token);
        List<AppUser> findByCompanyId(Long companyId);
    }

    public interface Recs extends JpaRepository<Rec, Long> {
        List<Rec> findByCompanyIdAndKind(Long companyId, String kind);
    }
}
