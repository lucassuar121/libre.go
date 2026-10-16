package com.librego;

import jakarta.persistence.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Entidades y repositorios. Rec guarda los datos de cada empresa (productos, ventas, turnos...) como JSON. */
public class Models {

    @Entity public static class Company {
        @Id @GeneratedValue public Long id;
        public String name;
        /** Marca reservada para la empresa de demostración aislada. */
        public Boolean demo = false;
        /** Versión del conjunto de datos de demostración; permite actualizarlo una sola vez al desplegar una nueva versión. */
        public Integer demoVersion = 0;
        /** Suscripción mensual: PENDIENTE_PAGO | ACTIVA | VENCIDA (null = empresa anterior a la suscripción, se migra al arrancar). */
        public String subStatus;
        public Instant subStart;
        public Instant subExpires;
        /** Suscripción con renovación automática en Mercado Pago (preapproval). */
        public String mpPreapprovalId;
        public String mpSubRef;            // external_reference de la suscripción (identifica a la empresa en los pagos)
        public String mpPreapprovalStatus; // pending | authorized | paused | cancelled (espejo del estado en Mercado Pago)
        public BigDecimal mpSubAmount;     // importe acordado al suscribirse
        /** Baja: fecha en que el jefe la pidió y fecha en que Mercado Pago confirmó la cancelación. */
        public Instant subCancelRequestedAt;
        public Instant subCancelConfirmedAt;
    }

    /** Registro de eventos relevantes de suscripciones y bajas (auditoría). */
    @Entity public static class AuditEvent {
        @Id @GeneratedValue public Long id;
        public Instant at;
        public Long companyId;
        public String companyName;
        public String type;
        @Column(length = 2000) public String detail;
    }

    /** Pago de la suscripción. "reference" identifica la orden (y la empresa); "mpPaymentId" es el id del pago en Mercado Pago. */
    @Entity public static class Payment {
        @Id @GeneratedValue public Long id;
        public Long companyId;
        @Column(unique = true) public String reference;
        @Column(unique = true) public String mpPaymentId;
        public String preferenceId;
        public String source;   // MERCADOPAGO | MANUAL
        public String status;   // PENDING | APPROVED | REJECTED
        public String mpStatus; // estado crudo informado por Mercado Pago
        public BigDecimal amount;
        public String currency;
        public Instant createdAt;
        public Instant paidAt;
        public Instant periodStart;
        public Instant periodEnd;
        public String companyName;   // para conservar el registro aunque la empresa se elimine
        public String preapprovalId;
    }


    /** Contratación pública: no es una cuenta ni una empresa; solo se convierte en cuenta tras verificar el primer pago. */
    @Entity public static class SignupIntent {
        @Id @GeneratedValue public Long id;
        @Column(unique = true, length = 64) public String tokenHash;
        @Column(unique = true, length = 100) public String externalReference;
        @Column(unique = true) public String mpPreapprovalId;
        public String payerEmail;
        public String status; // PENDING | PAID | COMPLETED | REJECTED
        public String mpStatus;
        public String mpPaymentId;
        public BigDecimal amount;
        public Instant createdAt;
        public Instant paidAt;
        public Instant expiresAt;
    }

    public interface SignupIntents extends JpaRepository<SignupIntent, Long> {
        Optional<SignupIntent> findByTokenHash(String tokenHash);
        @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<SignupIntent> findWithLockByTokenHash(String tokenHash);
        Optional<SignupIntent> findByExternalReference(String externalReference);
        Optional<SignupIntent> findByMpPreapprovalId(String mpPreapprovalId);
        Optional<SignupIntent> findByMpPaymentId(String mpPaymentId);
    }

    /** Configuración fiscal cifrada y separada por empresa. */
    @Entity public static class FiscalConfig {
        @Id @GeneratedValue public Long id;
        @Column(unique = true, nullable = false) public Long companyId;
        public String cuit;
        public String legalName;
        public String ivaCondition;
        public Integer pointOfSale;
        public String environment; // HOMOLOGACION | PRODUCCION
        @Column(length = 3000000) public String encryptedPkcs12;
        @Column(length = 4000) public String encryptedCertificatePassword;
        public Instant updatedAt;
    }

    /** Factura fiscal autorizada o rechazada por ARCA; nunca se simula el CAE. */
    @Entity @Table(uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_company_sale", columnNames = {"company_id", "sale_record_id"})) public static class FiscalInvoice {
        @Id @GeneratedValue public Long id;
        @Column(name = "company_id", nullable = false) public Long companyId;
        @Column(name = "sale_record_id", nullable = false) public Long saleRecordId;
        public String invoiceType;
        public String issuerCuit;
        public String issuerName;
        public Integer pointOfSale;
        public Long invoiceNumber;
        public String issueDate;
        public String buyerName;
        public String buyerDocType;
        public String buyerDocNumber;
        public Double total;
        public Double netAmount;
        public Double vatAmount;
        @Column(length = 100000) public String itemsJson;
        public String cae;
        public String caeExpiration;
        public String status; // AUTHORIZED | REJECTED
        public String environment;
        @Column(length = 4000) public String responseSummary;
        public Instant createdAt;
    }

    public interface FiscalConfigs extends JpaRepository<FiscalConfig, Long> {
        Optional<FiscalConfig> findByCompanyId(Long companyId);
    }
    public interface FiscalInvoices extends JpaRepository<FiscalInvoice, Long> {
        List<FiscalInvoice> findByCompanyIdOrderByIdDesc(Long companyId);
        Optional<FiscalInvoice> findByCompanyIdAndSaleRecordId(Long companyId, Long saleRecordId);
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

    public interface Companies extends JpaRepository<Company, Long> {
        /**
         * Lee la empresa bloqueando su fila hasta terminar la transacción (serializa pagos/notificaciones concurrentes).
         * Es una consulta derivada del nombre del método (sin JPQL): así no depende del nombre de entidad de las clases anidadas.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        Optional<Company> findWithLockById(Long id);
        /** Proyecciones: solo el id, para bloquear y releer la empresa después (sin cargarla antes). */
        Optional<IdRef> findProjectedByMpPreapprovalId(String preapprovalId);
        Optional<IdRef> findProjectedByMpSubRef(String subRef);
    }

    public interface IdRef { Long getId(); }

    public interface Audits extends JpaRepository<AuditEvent, Long> {
        List<AuditEvent> findTop100ByOrderByIdDesc();
    }

    /** Proyección con solo el id de la empresa. */
    public interface CompanyRef { Long getCompanyId(); }

    public interface Payments extends JpaRepository<Payment, Long> {
        Optional<Payment> findByReference(String reference);
        Optional<Payment> findByMpPaymentId(String mpPaymentId);
        List<Payment> findByCompanyIdOrderByCreatedAtDesc(Long companyId);
        /** Solo la empresa de una orden (proyección: no carga la entidad en memoria, para releerla después de bloquear la empresa). */
        Optional<CompanyRef> findProjectedByReference(String reference);
    }

    public interface Users extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByUsername(String username);
        Optional<AppUser> findByEmailIgnoreCase(String email);
        Optional<AppUser> findByResetToken(String token);
        List<AppUser> findByCompanyId(Long companyId);
    }

    public interface Recs extends JpaRepository<Rec, Long> {
        List<Rec> findByCompanyIdAndKind(Long companyId, String kind);
        /** Últimos 300 registros de un tipo (para no leer toda la actividad cada vez). */
        List<Rec> findTop300ByCompanyIdAndKindOrderByIdDesc(Long companyId, String kind);
        List<Rec> findByCompanyIdAndKindOrderByIdDesc(Long companyId, String kind);
        void deleteByCompanyId(Long companyId);
    }
}
