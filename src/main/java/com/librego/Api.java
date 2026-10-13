package com.librego;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.librego.Models.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Transactional
public class Api {

    static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    private static final Logger LOG = LoggerFactory.getLogger(Api.class);
    static final int MINPW = 8;
    static final String PWMSG = "La contraseña debe tener al menos " + MINPW + " caracteres.";
    static final String TOKMSG = "El enlace no es válido o ya venció. Pedí uno nuevo.";

    private final Companies cos;
    private final Users users;
    private final Recs recs;
    private final ObjectMapper om;
    private final Mailer mailer;
    private final HttpServletRequest req;
    private final Payments pays;
    private final Audits audits;
    private final AuditService audit;
    private final SubscriptionJobs jobs;
    private final MercadoPagoClient mp;
    private final SignupIntents signupIntents;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    private final SecureRandom rnd = new SecureRandom();
    @Value("${app.base-url}") private String baseUrl;
    @Value("${app.global-admin.username}") private String adminUser;
    @Value("${app.reset-minutes:30}") private long resetMinutes;
    @Value("${app.timezone:America/Argentina/Cordoba}") private String tz;
    @Value("${app.contact-email:}") private String contactEmail;
    /** Precio mensual de la suscripción: único lugar donde se define (application.properties / variable SUBSCRIPTION_PRICE). */
    @Value("${app.subscription.price}") private BigDecimal price;
    @Value("${app.subscription.title:Suscripción mensual Libre.go}") private String subTitle;
    @Value("${app.subscription.warn-days:7}") private long warnDays;
    @Value("${app.subscription.delete-grace-days:0}") private long deleteGraceDays;
    private final Map<String, List<Instant>> hits = new java.util.concurrent.ConcurrentHashMap<>();

    public Api(Companies cos, Users users, Recs recs, ObjectMapper om, Mailer mailer, HttpServletRequest req, Payments pays, Audits audits, AuditService audit, SubscriptionJobs jobs, MercadoPagoClient mp, SignupIntents signupIntents) {
        this.cos = cos; this.users = users; this.recs = recs; this.om = om; this.mailer = mailer; this.req = req; this.pays = pays; this.audits = audits; this.audit = audit; this.jobs = jobs; this.mp = mp; this.signupIntents = signupIntents;
    }

    // ---------- utilidades ----------
    static ResponseStatusException err(int code, String msg) { return new ResponseStatusException(HttpStatus.valueOf(code), msg); }
    static Map<String, Object> ok() { return Map.of("ok", true); }
    static long num(Object o) { return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o).trim()); }
    static double dbl(Object o) { return o == null || String.valueOf(o).isBlank() ? 0 : o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o).trim()); }
    static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    static boolean isBoss(AppUser u) { return "jefe".equals(u.role); }
    static String pad(long n) { return String.format("%05d", n); }
    static String sha(String t) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static final String HM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    LocalDate dayOf(Object at) { return Instant.parse(String.valueOf(at)).atZone(ZoneId.of(tz)).toLocalDate(); }

    List<Map<String, Object>> activeDefs(Long c) {
        return list(c, "shiftdefs").stream().filter(x -> Boolean.TRUE.equals(x.get("active"))).collect(Collectors.toList());
    }

    /** Turno configurado por el jefe y activo (si no existe o está desactivado, se rechaza). */
    Map<String, Object> pickDef(Long c, Object id) {
        long did = num(id);
        return activeDefs(c).stream().filter(x -> num(x.get("id")) == did).findFirst()
                .orElseThrow(() -> err(400, "El turno elegido no existe o está desactivado."));
    }

    static void stampDef(Map<String, Object> rec, Map<String, Object> def) {
        rec.put("shiftDefId", def.get("id")); rec.put("shiftName", def.get("name"));
        rec.put("shiftFrom", def.get("from")); rec.put("shiftTo", def.get("to"));
    }

    /** ¿La venta pertenece al mismo turno que el fichaje abierto? Compara el turno configurado (cualquier mostrador) o, sin turnos, el propio fichaje. */
    static boolean sameTurn(Map<String, Object> open, Map<String, Object> sale) {
        if (open.get("shiftDefId") != null) return sale.get("shiftDefId") != null && num(sale.get("shiftDefId")) == num(open.get("shiftDefId"));
        return sale.get("shiftId") != null && num(sale.get("shiftId")) == num(open.get("id"));
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    /** Total de unidades de un ingreso por cajas/paquetes (cajas x unidades por caja). El cálculo se hace en el servidor. */
    static long boxTotal(long boxes, long perBox) {
        if (boxes < 1 || perBox < 1) throw err(400, "Indicá la cantidad de cajas y las unidades por caja (enteros mayores a 0).");
        try {
            long t = Math.multiplyExact(boxes, perBox);
            if (t > 1_000_000_000L) throw err(400, "La cantidad es demasiado grande.");
            return t;
        } catch (ArithmeticException e) { throw err(400, "La cantidad es demasiado grande."); }
    }

    @ExceptionHandler({NumberFormatException.class, NullPointerException.class, ClassCastException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> bad(Exception e) {
        LOG.warn("Petición inválida: {}", e.toString());
        return Map.of("message", "Datos inválidos.");
    }

    /**
     * Devuelve el mensaje de las respuestas de error PROPIAS (permisos, validaciones, bloqueo de suscripción).
     * Los errores inesperados no exponen detalles internos porque server.error.include-message=never.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> rse(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode().value()).body(Map.of("message", e.getReason() == null ? "Error" : e.getReason()));
    }

    // ---------- límites de intentos (anti fuerza bruta / spam) ----------
    boolean tooMany(String key, int max, long windowSeconds) {
        List<Instant> l = hits.get(key);
        if (l == null) return false;
        Instant limit = Instant.now().minusSeconds(windowSeconds);
        l.removeIf(t -> t.isBefore(limit));
        if (l.isEmpty()) hits.remove(key, l);
        return l.size() >= max;
    }

    void hit(String key) { hits.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(Instant.now()); }

    /** Cuenta un intento y rechaza con 429 si se superó el máximo dentro de la ventana. */
    void throttle(String key, int max, long windowSeconds, String msg) {
        if (tooMany(key, max, windowSeconds)) throw err(429, msg);
        hit(key);
    }

    /** IP del cliente (con server.forward-headers-strategy=native detrás de Cloud Run es la IP real, no la del proxy). */
    String ip() { return String.valueOf(req.getRemoteAddr()); }

    /** Bloquea la fila de la empresa hasta terminar la transacción: evita números de venta repetidos y stock pisado entre mostradores. */
    void lockCompany(Long companyId) { cos.findWithLockById(companyId); }

    // ---------- autenticación y permisos ----------
    /** Usuario real de la sesión (sin ningún tipo de suplantación). */
    AppUser real(HttpSession s) {
        Object id = s.getAttribute("uid");
        if (id == null) throw err(401, "Iniciá sesión.");
        return users.findById((Long) id).orElseThrow(() -> err(401, "Iniciá sesión."));
    }

    /** Solo el usuario configurado como administrador global (rol "global" y nombre de usuario exacto). */
    boolean isGlobal(AppUser u) { return "global".equals(u.role) && adminUser.equalsIgnoreCase(u.username); }

    /**
     * Usuario que actúa en la petición. Para usuarios normales es siempre su propia cuenta y su propia empresa:
     * el encabezado X-Company se IGNORA. Solo el administrador global puede indicar una empresa con ese encabezado,
     * y entonces actúa como jefe de esa empresa (usuario virtual, nunca se guarda).
     */
    AppUser me(HttpSession s) {
        AppUser u = real(s);
        if (!isGlobal(u)) {
            if ("global".equals(u.role)) throw err(403, "Acceso denegado.");
            enforce(u); // BLOQUEO por suscripción: ninguna operación normal si la empresa no está ACTIVA
            return u;
        }
        String h = req.getHeader("X-Company");
        if (h == null || h.isBlank()) throw err(403, "Elegí una empresa desde Administración Global.");
        Company c = cos.findById(Long.parseLong(h.trim())).orElseThrow(() -> err(404, "La empresa no existe."));
        AppUser v = new AppUser();
        v.id = u.id; v.companyId = c.id; v.name = "Admin global (" + u.username + ")"; v.username = u.username; v.role = "jefe";
        return v;
    }

    AppUser boss(HttpSession s) {
        AppUser u = me(s);
        if (!isBoss(u)) throw err(403, "Solo el jefe puede hacer esto.");
        return u;
    }

    AppUser global(HttpSession s) {
        AppUser u = real(s);
        if (!isGlobal(u)) throw err(403, "Solo el administrador global puede hacer esto.");
        return u;
    }

    // ---------- acceso a datos ----------
    Map<String, Object> rd(Rec r) {
        try {
            Map<String, Object> m = new LinkedHashMap<>(om.readValue(r.payload, new TypeReference<Map<String, Object>>() {}));
            m.put("id", r.id);
            return m;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    Rec wr(Rec r, Map<String, Object> m) {
        m.remove("id");
        try { r.payload = om.writeValueAsString(m); } catch (Exception e) { throw new RuntimeException(e); }
        return recs.save(r);
    }

    Rec mk(Long company, String kind, Map<String, Object> m) {
        Rec r = new Rec();
        r.companyId = company; r.kind = kind;
        return wr(r, new LinkedHashMap<>(m));
    }

    List<Map<String, Object>> list(Long company, String kind) {
        return recs.findByCompanyIdAndKind(company, kind).stream().map(this::rd).collect(Collectors.toList());
    }

    /** Busca un registro y verifica que pertenezca a la empresa indicada (aislamiento entre empresas). */
    Rec find(Long company, String kind, Object id) {
        Rec r = recs.findById(num(id)).orElseThrow(() -> err(404, "No existe."));
        if (!company.equals(r.companyId) || !kind.equals(r.kind)) throw err(404, "No existe.");
        return r;
    }

    void log(AppUser u, String text) {
        if (u.companyId == null) return;
        mk(u.companyId, "log", Map.of("at", Instant.now().toString(), "userId", u.id, "user", u.name, "text", text));
    }

    Map<String, Object> openShift(AppUser u) {
        return list(u.companyId, "shifts").stream()
                .filter(x -> x.get("end") == null && num(x.get("userId")) == u.id)
                .findFirst().orElse(null);
    }

    // ---------- sesión y cuentas ----------
    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> b, HttpSession s) {
        // El registro público anterior queda bloqueado: las altas nuevas deben pasar por pago confirmado.
        throw err(403, "Para crear una cuenta primero tenés que contratar y completar el pago desde la página principal.");
    }

    /** Inicia una contratación sin crear empresa ni usuario. El checkout y los datos de tarjeta se procesan en Mercado Pago. */
    @PostMapping("/public/signup/checkout")
    public Map<String, Object> signupCheckout(@RequestBody Map<String, Object> b) {
        throttle("signup-checkout|" + ip(), 5, 3600, "Se iniciaron demasiadas contrataciones desde tu conexión. Probá más tarde.");
        if (!mp.configured()) throw err(503, "El pago online todavía no está disponible. Contactá a soporte.");
        String email = str(b.get("payerEmail")).toLowerCase();
        if (!email.matches(EMAIL) || email.length() > 150) throw err(400, "Ingresá un email válido para Mercado Pago.");
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString().replace("-", "");
        String ref = "SIGNUP-" + UUID.randomUUID();
        SignupIntent intent = new SignupIntent();
        intent.tokenHash = sha(token); intent.externalReference = ref; intent.payerEmail = email;
        intent.status = "PENDING"; intent.amount = price; intent.createdAt = Instant.now(); intent.expiresAt = Instant.now().plus(Duration.ofDays(2));
        Map<String, Object> recurring = new LinkedHashMap<>();
        recurring.put("frequency", 1); recurring.put("frequency_type", "months"); recurring.put("transaction_amount", price); recurring.put("currency_id", "ARS");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", subTitle); body.put("external_reference", ref); body.put("payer_email", email);
        body.put("auto_recurring", recurring); body.put("back_url", baseUrl + "/?contratacion=" + token); body.put("status", "pending");
        Map<String, Object> pre;
        try { pre = mp.createPreapproval(body); }
        catch (Exception e) { LOG.warn("Mercado Pago (contratación pública): {}", e.getMessage()); throw err(502, "No se pudo iniciar el pago. Probá de nuevo en unos minutos."); }
        String url = mp.checkoutUrl(pre), preId = str(pre.get("id"));
        if (url.isEmpty() || preId.isEmpty()) throw err(502, "Mercado Pago no devolvió un enlace de pago válido.");
        intent.mpPreapprovalId = preId; intent.mpStatus = str(pre.get("status"));
        signupIntents.save(intent);
        return Map.of("url", url, "token", token, "amount", price, "currency", "ARS");
    }

    /** Consulta pública de una contratación por token aleatorio; no revela datos privados. */
    @GetMapping("/public/signup/status")
    public Map<String, Object> signupStatus(@RequestParam String token) {
        if (token == null || token.length() > 100) throw err(400, "Enlace de contratación inválido.");
        SignupIntent in = signupIntents.findByTokenHash(sha(token)).orElseThrow(() -> err(404, "No encontramos esta contratación. Volvé a iniciar el proceso."));
        if (in.expiresAt != null && in.expiresAt.isBefore(Instant.now()) && !"PAID".equals(in.status) && !"COMPLETED".equals(in.status))
            throw err(410, "Esta contratación venció. Iniciá una nueva contratación.");
        return Map.of("status", in.status, "paid", "PAID".equals(in.status), "completed", "COMPLETED".equals(in.status), "amount", in.amount == null ? price : in.amount, "currency", "ARS");
    }

    /** Crea la empresa y el primer usuario solamente después de que el webhook haya marcado el pago como aprobado. */
    @PostMapping("/public/signup/register")
    public Map<String, Object> completeSignup(@RequestBody Map<String, Object> b, HttpSession s) {
        String token = str(b.get("token"));
        if (token.isEmpty() || token.length() > 100) throw err(400, "Contratación inválida.");
        SignupIntent in = signupIntents.findWithLockByTokenHash(sha(token)).orElseThrow(() -> err(404, "No encontramos esta contratación."));
        if (!"PAID".equals(in.status)) throw err(402, "Todavía no confirmamos el pago. No podés crear la cuenta antes de que Mercado Pago lo apruebe.");
        String co = str(b.get("company")), name = str(b.get("name")), un = str(b.get("username")).toLowerCase();
        String em = str(b.get("email")).toLowerCase(), pw = str(b.get("password"));
        if (co.isEmpty() || name.isEmpty() || un.isEmpty() || em.isEmpty()) throw err(400, "Completá todos los campos.");
        if (co.length() > 120 || name.length() > 120 || un.length() > 50 || em.length() > 150) throw err(400, "Alguno de los datos es demasiado largo.");
        if (!em.matches(EMAIL)) throw err(400, "Ingresá un email válido.");
        if (pw.length() < MINPW) throw err(400, PWMSG);
        if (users.findByUsername(un).isPresent()) throw err(400, "Ese usuario ya existe. Probá con otro.");
        if (users.findByEmailIgnoreCase(em).isPresent()) throw err(400, "Ese email ya tiene una cuenta.");
        Company c = new Company(); c.name = co; c.subStatus = "ACTIVA"; c.subStart = in.paidAt == null ? Instant.now() : in.paidAt;
        c.subExpires = c.subStart.atZone(ZoneId.of(tz)).plusMonths(1).toInstant(); c.mpPreapprovalId = in.mpPreapprovalId;
        c.mpSubRef = in.externalReference; c.mpPreapprovalStatus = in.mpStatus; c.mpSubAmount = in.amount;
        c = cos.save(c);
        AppUser u = new AppUser(); u.companyId = c.id; u.name = name; u.username = un; u.email = em; u.passHash = enc.encode(pw); u.role = "jefe";
        u = users.save(u);
        Payment p = new Payment(); p.companyId = c.id; p.companyName = c.name; p.reference = "SIGNUP-PAY-" + in.mpPaymentId;
        p.mpPaymentId = in.mpPaymentId; p.preapprovalId = in.mpPreapprovalId; p.source = "MERCADOPAGO"; p.status = "APPROVED";
        p.mpStatus = "approved"; p.amount = in.amount; p.currency = "ARS"; p.createdAt = in.createdAt; p.paidAt = in.paidAt;
        p.periodStart = c.subStart; p.periodEnd = c.subExpires; pays.save(p);
        in.status = "COMPLETED"; signupIntents.save(in);
        req.changeSessionId(); s.setAttribute("uid", u.id); audit.record(c.id, c.name, "SIGNUP_PAID_AND_CREATED", "Empresa creada después de confirmar el primer pago de Mercado Pago.");
        return bootFor(u);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> b, HttpSession s) {
        String un = str(b.get("username")).toLowerCase(), ukey = "login|" + ip() + "|" + un, ikey = "loginip|" + ip();
        if (tooMany(ukey, 10, 900) || tooMany(ikey, 50, 900)) throw err(429, "Demasiados intentos fallidos. Probá de nuevo en unos minutos.");
        AppUser u = users.findByUsername(un).orElse(null);
        if (u == null || !enc.matches(str(b.get("password")), u.passHash)) {
            hit(ukey); hit(ikey);
            throw err(400, "Usuario o contraseña incorrectos.");
        }
        hits.remove(ukey);
        req.changeSessionId(); // evita la fijación de sesión
        s.setAttribute("uid", u.id);
        if (isGlobal(u)) return globalBoot(u);
        if ("global".equals(u.role)) throw err(403, "Acceso denegado.");
        log(u, "Inició sesión");
        return bootFor(u);
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpSession s) { s.invalidate(); return ok(); }

    /** Cambio de contraseña del usuario logueado (pide la actual). */
    @PostMapping("/auth/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = real(s);
        if (!enc.matches(str(b.get("current")), u.passHash)) throw err(400, "La contraseña actual no es correcta.");
        String pw = str(b.get("password"));
        if (pw.length() < MINPW) throw err(400, PWMSG);
        if (!pw.equals(str(b.get("confirm")))) throw err(400, "Las contraseñas no coinciden.");
        u.passHash = enc.encode(pw);
        users.save(u);
        log(u, "Cambió su contraseña");
        return ok();
    }

    /**
     * Paso 1 de la recuperación. La respuesta es siempre la misma exista o no el email (no revela usuarios).
     * Se genera un token aleatorio de 256 bits; en la base solo se guarda su hash SHA-256.
     */
    @PostMapping("/auth/forgot")
    public Map<String, Object> forgot(@RequestBody Map<String, Object> b) {
        users.findByEmailIgnoreCase(str(b.get("email"))).ifPresent(u -> {
            if (isGlobal(u) || u.email == null) return;
            Instant now = Instant.now();
            if (u.resetExp != null && u.resetExp.isAfter(now.plusSeconds(resetMinutes * 60 - 60))) return; // máx. 1 pedido por minuto
            byte[] raw = new byte[32];
            rnd.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            u.resetToken = sha(token);
            u.resetExp = now.plusSeconds(resetMinutes * 60);
            users.save(u);
            String link = baseUrl + "/?reset=" + token;
            String text = "Hola " + u.name + ",\n\nRecibimos un pedido para restablecer tu contraseña de Libre.go.\n"
                + "Entrá a este enlace para crear una nueva (vale " + resetMinutes + " minutos y se puede usar una sola vez):\n" + link
                + "\n\nSi no lo pediste vos, ignorá este mensaje: tu contraseña no cambia.";
            String html = "<div style='font-family:Arial,sans-serif;max-width:520px;margin:auto'><h2>Libre.go</h2><p>Hola <b>" + TicketPdf.esc(u.name)
                + "</b>,</p><p>Recibimos un pedido para restablecer tu contraseña. El enlace vale <b>" + resetMinutes
                + " minutos</b> y se puede usar una sola vez.</p><p><a href='" + link
                + "' style='background:#0f4c4a;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none'>Crear nueva contraseña</a></p>"
                + "<p style='color:#666;font-size:13px'>Si el botón no funciona, copiá este enlace en el navegador:<br>" + link
                + "</p><p style='color:#666;font-size:13px'>Si no lo pediste vos, ignorá este mensaje: tu contraseña no cambia.</p></div>";
            mailer.send(u.email, "Libre.go - Recuperar contraseña", text, html, null, null);
        });
        return ok();
    }

    /** Paso 2: valida token (existe, no venció), que ambas contraseñas coincidan, guarda el hash y consume el token. */
    @PostMapping("/auth/reset")
    public Map<String, Object> reset(@RequestBody Map<String, Object> b) {
        String token = str(b.get("token"));
        if (token.isEmpty()) throw err(400, TOKMSG);
        AppUser u = users.findByResetToken(sha(token)).orElseThrow(() -> err(400, TOKMSG));
        if (u.resetExp == null || u.resetExp.isBefore(Instant.now())) throw err(400, TOKMSG);
        String pw = str(b.get("password"));
        if (pw.length() < MINPW) throw err(400, PWMSG);
        if (!pw.equals(str(b.get("confirm")))) throw err(400, "Las contraseñas no coinciden.");
        u.passHash = enc.encode(pw);
        u.resetToken = null;
        u.resetExp = null;
        users.save(u);
        log(u, "Recuperó su contraseña");
        return ok();
    }

    // ---------- datos de la empresa ----------
    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap(HttpSession s) {
        AppUser r = real(s);
        String h = req.getHeader("X-Company");
        if (isGlobal(r)) {
            if (h == null || h.isBlank()) return globalBoot(r);
            Map<String, Object> m = boot(me(s)); // el administrador global no se bloquea por la suscripción
            m.put("gadmin", true);
            return m;
        }
        return bootFor(r);
    }

    Map<String, Object> pub(AppUser x) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", x.id); o.put("name", x.name); o.put("username", x.username); o.put("email", x.email); o.put("role", x.role);
        return o;
    }

    /** Panel del administrador global: todas las empresas y todos los usuarios. */
    Map<String, Object> globalBoot(AppUser u) {
        Map<Long, String> names = new HashMap<>();
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Company c : cos.findAll()) {
            names.put(c.id, c.name);
            List<Map<String, Object>> sl = list(c.id, "sales");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", c.id); o.put("name", c.name);
            o.put("users", users.findByCompanyId(c.id).size());
            o.put("products", recs.findByCompanyIdAndKind(c.id, "products").size());
            o.put("clients", recs.findByCompanyIdAndKind(c.id, "clients").size());
            o.put("sales", sl.size());
            o.put("total", sl.stream().mapToDouble(x -> dbl(x.get("total"))).sum());
            o.put("subStatus", effStatus(c));
            o.put("subExpires", c.subExpires == null ? null : c.subExpires.toString());
            cs.add(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", pub(u));
        m.put("companies", cs);
        m.put("audit", audits.findTop100ByOrderByIdDesc().stream().map(a -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.id); o.put("at", a.at.toString()); o.put("companyName", a.companyName); o.put("type", a.type); o.put("detail", a.detail);
            return o;
        }).collect(Collectors.toList()));
        m.put("leads", list(0L, "lead").stream().sorted((x, y) -> Long.compare(num(y.get("id")), num(x.get("id")))).collect(Collectors.toList()));
        m.put("users", users.findAll().stream().map(x -> {
            Map<String, Object> o = pub(x);
            o.put("company", x.companyId == null ? "—" : names.getOrDefault(x.companyId, "?"));
            return o;
        }).collect(Collectors.toList()));
        return m;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> boot(AppUser u) {
        Long c = u.companyId;
        boolean boss = isBoss(u);
        List<Map<String, Object>> sales = list(c, "sales");
        List<Map<String, Object>> shifts = list(c, "shifts");
        if (!boss) shifts = shifts.stream().filter(x -> num(x.get("userId")) == u.id && x.get("end") == null).collect(Collectors.toList());
        addCash(shifts, sales); // la caja se calcula con todas las ventas, antes de filtrar lo que ve el empleado
        if (!boss) { // empleado: solo las ventas de SU turno actual y del DÍA actual (cualquier mostrador del mismo turno)
            Map<String, Object> open = shifts.stream().filter(x -> x.get("end") == null).findFirst().orElse(null);
            LocalDate today = LocalDate.now(ZoneId.of(tz));
            sales = sales.stream().filter(x -> open != null && today.equals(dayOf(x.get("at"))) && sameTurn(open, x)).collect(Collectors.toList());
        }
        List<Map<String, Object>> prods = list(c, "products");
        if (!boss) { // el empleado no ve costos ni ganancias
            prods.forEach(p -> p.remove("buyPrice"));
            sales.forEach(x -> ((List<Map<String, Object>>) x.get("items")).forEach(i -> i.remove("cost")));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", pub(u));
        m.put("company", cos.findById(c).map(x -> x.name).orElse(""));
        cos.findById(c).ifPresent(co -> m.put("subscription", subInfo(co, boss)));
        m.put("products", prods);
        m.put("clients", list(c, "clients"));
        m.put("suppliers", list(c, "suppliers"));
        m.put("sales", sales);
        m.put("moves", boss ? list(c, "moves") : new ArrayList<>()); // el historial de movimientos es solo del jefe
        m.put("purchases", boss ? list(c, "purchases") : new ArrayList<>());
        m.put("tz", tz);
        m.put("shifts", shifts);
        List<Map<String, Object>> defs = list(c, "shiftdefs");
        if (!boss) defs = defs.stream().filter(x -> Boolean.TRUE.equals(x.get("active"))).collect(Collectors.toList());
        defs.sort((x, y) -> str(x.get("from")).compareTo(str(y.get("from"))));
        m.put("shiftDefs", defs);
        m.put("openShift", shifts.stream().filter(x -> x.get("end") == null && num(x.get("userId")) == u.id).findFirst().orElse(null));
        m.put("users", new ArrayList<>());
        m.put("log", new ArrayList<>());
        if (boss) {
            m.put("users", users.findByCompanyId(c).stream().map(this::pub).collect(Collectors.toList()));
            m.put("log", recs.findTop300ByCompanyIdAndKindOrderByIdDesc(c, "log").stream().map(this::rd).collect(Collectors.toList()));
        }
        return m;
    }

    void addCash(List<Map<String, Object>> shifts, List<Map<String, Object>> sales) {
        for (Map<String, Object> sh : shifts) {
            double sold = 0, cash = 0, card = 0, qr = 0;
            int n = 0;
            for (Map<String, Object> x : sales) {
                if (x.get("shiftId") == null || num(x.get("shiftId")) != num(sh.get("id"))) continue;
                double t = dbl(x.get("total"));
                String p = str(x.get("payment"));
                sold += t; n++;
                if (p.equals("efectivo")) cash += t; else if (p.equals("tarjeta")) card += t; else qr += t;
            }
            sh.put("cash", Map.of("sold", sold, "cash", cash, "card", card, "qr", qr, "count", n));
        }
    }

    // ---------- administración global ----------
    @PutMapping("/admin/companies/{id}")
    public Map<String, Object> renameCompany(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        global(s);
        String n = str(b.get("name"));
        if (n.isEmpty()) throw err(400, "Completá el nombre de la empresa.");
        Company c = cos.findById(id).orElseThrow(() -> err(404, "La empresa no existe."));
        c.name = n;
        cos.save(c);
        return ok();
    }

    /** Eliminar una empresa completa: solo el administrador global y con confirmación explícita. Afecta únicamente a esa empresa. */
    @DeleteMapping("/admin/companies/{id}")
    public Map<String, Object> deleteCompany(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> b, HttpSession s) {
        AppUser g = global(s);
        if (b == null || !Boolean.TRUE.equals(b.get("confirm"))) throw err(400, "Falta la confirmación para eliminar la empresa.");
        Company c = cos.findById(id).orElseThrow(() -> err(404, "La empresa no existe."));
        List<AppUser> us = users.findByCompanyId(c.id);
        if (us.stream().anyMatch(x -> x.id.equals(g.id) || "global".equals(x.role))) throw err(400, "Esta empresa no se puede eliminar.");
        recs.deleteByCompanyId(c.id);
        users.deleteAll(us);
        cos.delete(c); // el historial de pagos se conserva (registro contable) y queda un evento de auditoría
        audit.record(c.id, c.name, "COMPANY_DELETED_BY_ADMIN", "Eliminada por " + g.username + " con " + us.size() + " usuario(s).");
        LOG.info("Empresa eliminada: {} (id {}) por {}", c.name, c.id, g.username);
        return ok();
    }

    /** Formulario público "Quiero contratar": no requiere sesión. Guarda la solicitud (companyId 0) y la ve el administrador global. */
    @PostMapping("/public/contact")
    public Map<String, Object> contact(@RequestBody Map<String, Object> b) {
        throttle("contact|" + ip(), 5, 3600, "Recibimos varias solicitudes desde tu conexión. Probá de nuevo más tarde.");
        Instant now = Instant.now();
        String co = cut(str(b.get("company")), 120), name = cut(str(b.get("name")), 120), em = str(b.get("email")).toLowerCase();
        String phone = cut(str(b.get("phone")), 40), users = cut(str(b.get("users")), 20), msg = cut(str(b.get("message")), 1000);
        if (co.isEmpty() || name.isEmpty()) throw err(400, "Completá el nombre de la empresa y el del responsable.");
        if (!em.matches(EMAIL) || em.length() > 150) throw err(400, "Ingresá un email válido.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", now.toString()); m.put("company", co); m.put("name", name); m.put("email", em);
        m.put("phone", phone); m.put("users", users); m.put("message", msg);
        mk(0L, "lead", m);
        if (!contactEmail.isBlank()) {
            String text = "Nueva solicitud de contratación\n\nEmpresa: " + co + "\nResponsable: " + name + "\nEmail: " + em + "\nTeléfono: " + phone
                + "\nUsuarios aprox.: " + users + "\nMensaje: " + msg;
            mailer.send(contactEmail, "Libre.go - Nueva solicitud de contratación: " + co, text, "<pre style='font-family:Arial,sans-serif'>" + TicketPdf.esc(text) + "</pre>", null, null);
        }
        return ok();
    }

    @DeleteMapping("/admin/leads/{id}")
    public Map<String, Object> deleteLead(@PathVariable Long id, HttpSession s) {
        global(s);
        recs.delete(find(0L, "lead", id));
        return ok();
    }

    // ---------- productos, clientes, proveedores ----------
    static final Map<String, List<String>> F = Map.of(
            "products", List.of("type", "title", "author", "isbn", "category", "buyPrice", "sellPrice", "stock"),
            "clients", List.of("name", "email", "phone"),
            "suppliers", List.of("name", "contact", "phone", "email", "website"));
    static final Set<String> NUM = Set.of("buyPrice", "sellPrice", "stock");
    static final Map<String, String> LAB = Map.of("products", "producto", "clients", "cliente", "suppliers", "proveedor");

    void chk(String kind) { if (!F.containsKey(kind)) throw err(404, "No existe."); }
    static String nm(Map<String, Object> m) { return str(m.get("title")).isEmpty() ? str(m.get("name")) : str(m.get("title")); }

    Map<String, Object> clean(String kind, Map<String, Object> b, AppUser u, Map<String, Object> old) {
        Map<String, Object> m = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old);
        for (String f : F.get(kind)) {
            if (!b.containsKey(f)) continue;
            if (f.equals("buyPrice") && !isBoss(u) && old != null) continue; // el empleado no modifica costos existentes
            Object v;
            if (f.equals("stock")) v = Math.max(0L, Math.round(dbl(b.get(f))));
            else if (NUM.contains(f)) v = Math.max(0.0, dbl(b.get(f)));
            else v = str(b.get(f));
            m.put(f, v);
        }
        if (kind.equals("products")) {
            if (!"otro".equals(m.get("type"))) m.put("type", "libro");
            m.putIfAbsent("buyPrice", 0.0); m.putIfAbsent("sellPrice", 0.0); m.putIfAbsent("stock", 0L);
        }
        if (str(m.get(kind.equals("products") ? "title" : "name")).isEmpty()) throw err(400, "Completá el nombre.");
        return m;
    }

    @PostMapping("/{kind}")
    public Map<String, Object> create(@PathVariable String kind, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        chk(kind);
        if (kind.equals("products")) throw err(400, "Los productos nuevos se cargan desde el ingreso de stock.");
        Map<String, Object> m = clean(kind, b, u, null);
        mk(u.companyId, kind, m);
        log(u, "Creó " + LAB.get(kind) + ": " + nm(m));
        return ok();
    }

    @PutMapping("/{kind}/{id}")
    public Map<String, Object> update(@PathVariable String kind, @PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        chk(kind);
        if (kind.equals("products")) lockCompany(u.companyId);
        Rec r = find(u.companyId, kind, id);
        Map<String, Object> old = rd(r);
        Map<String, Object> m = clean(kind, b, u, old);
        wr(r, m);
        if (kind.equals("products")) { // cambiar el stock a mano queda registrado como ajuste
            long before = num(old.get("stock")), after = num(m.get("stock"));
            if (before != after) move(u, r.id, str(m.get("title")), "ajuste", Math.abs(after - before), before, after, "Edición del producto");
        }
        log(u, "Editó " + LAB.get(kind) + ": " + nm(m));
        return ok();
    }

    @DeleteMapping("/{kind}/{id}")
    public Map<String, Object> delete(@PathVariable String kind, @PathVariable Long id, HttpSession s) {
        AppUser u = me(s);
        chk(kind);
        if (kind.equals("products") && !isBoss(u)) throw err(403, "Solo el jefe puede eliminar productos.");
        Rec r = find(u.companyId, kind, id);
        Map<String, Object> m = rd(r);
        recs.delete(r);
        log(u, "Eliminó " + LAB.get(kind) + ": " + nm(m));
        return ok();
    }

    // ---------- stock y movimientos ----------
    /** Registra un movimiento con stock anterior y posterior. Tipos: entrada, venta, salida, ajuste, devolucion. */
    void move(AppUser u, long productId, String title, String type, long qty, long before, long after, String reason) {
        mk(u.companyId, "moves", Map.of("at", Instant.now().toString(), "productId", productId, "title", title, "type", type,
                "qty", qty, "before", before, "after", after, "reason", reason, "userId", u.id, "user", u.name));
    }

    /** Ingreso de un producto nuevo (libro u otro): título, precio de compra, precio de venta y unidades que entraron. */
    @PostMapping("/stock/new")
    public Map<String, Object> stockNew(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        lockCompany(u.companyId);
        long units = Math.round(dbl(b.get("units")));
        long boxes = Math.round(dbl(b.get("boxes"))), perBox = Math.round(dbl(b.get("perBox")));
        String detail = "Ingreso de producto nuevo";
        if (boxes > 0 || perBox > 0) { // ingreso por cajas/paquetes
            units = boxTotal(boxes, perBox);
            detail = detail + " (" + boxes + " cajas × " + perBox + " unidades)";
        }
        if (units < 1) throw err(400, "Indicá cuántas unidades entraron.");
        b.put("stock", units);
        Map<String, Object> m = clean("products", b, u, null);
        Rec r = mk(u.companyId, "products", m);
        move(u, r.id, str(m.get("title")), "entrada", units, 0, units, detail);
        log(u, "Ingresó producto nuevo: " + m.get("title") + " (" + units + " u.)");
        return ok();
    }

    @PostMapping("/stock/move")
    public Map<String, Object> stockMove(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        lockCompany(u.companyId);
        Rec r = find(u.companyId, "products", b.get("productId"));
        Map<String, Object> p = rd(r);
        String type = str(b.get("type"));
        if (!Set.of("entrada", "salida", "ajuste", "devolucion").contains(type)) throw err(400, "Tipo de movimiento inválido.");
        String reason = str(b.get("reason"));
        if ((type.equals("ajuste") || type.equals("salida")) && reason.isEmpty()) throw err(400, "Indicá el motivo del movimiento.");
        if (reason.isEmpty()) reason = type.equals("entrada") ? "Entrada de mercadería" : "Devolución de cliente";
        long boxes = Math.round(dbl(b.get("boxes"))), perBox = Math.round(dbl(b.get("perBox")));
        if (type.equals("entrada") && (boxes > 0 || perBox > 0)) { // ingreso por cajas/paquetes: cajas x unidades por caja
            b.put("qty", boxTotal(boxes, perBox));
            reason = reason + " (" + boxes + " cajas × " + perBox + " unidades)";
        }
        long q = Math.round(dbl(b.get("qty"))), have = num(p.get("stock")), after, qty;
        if (type.equals("ajuste")) {
            if (q < 0) throw err(400, "El stock real no puede ser negativo.");
            if (q == have) throw err(400, "El stock real coincide con el stock actual.");
            after = q; qty = Math.abs(after - have);
        } else {
            if (q < 1) throw err(400, "Cantidad inválida.");
            after = type.equals("salida") ? have - q : have + q;
            if (after < 0) throw err(400, "No podés sacar más de lo que hay (" + have + ").");
            qty = q;
        }
        p.put("stock", after);
        wr(r, p);
        move(u, r.id, str(p.get("title")), type, qty, have, after, reason);
        log(u, "Movimiento de stock (" + type + "): " + qty + "× " + p.get("title"));
        return ok();
    }

    // ---------- ventas ----------
    @SuppressWarnings("unchecked")
    @PostMapping("/sales")
    public Map<String, Object> sale(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        Long c = u.companyId;
        lockCompany(c);
        String pay = str(b.get("payment"));
        if (!Set.of("efectivo", "tarjeta", "qr").contains(pay)) throw err(400, "Elegí el medio de pago.");
        String ctype = str(b.get("customerType"));
        if (ctype.isEmpty()) ctype = "nuevo";
        if (!Set.of("registrado", "nuevo", "final").contains(ctype)) throw err(400, "Tipo de cliente inválido.");
        String fn = "", ln = "", em = "";
        if (ctype.equals("nuevo")) {
            Map<String, Object> cu = (Map<String, Object>) b.get("customer");
            if (cu == null) throw err(400, "Completá los datos del cliente.");
            fn = str(cu.get("firstName")); ln = str(cu.get("lastName")); em = str(cu.get("email")).toLowerCase();
            if (fn.isEmpty() || ln.isEmpty()) throw err(400, "Ingresá nombre y apellido del cliente.");
            if (!em.matches(EMAIL)) throw err(400, "Ingresá un correo electrónico válido para el cliente.");
        }
        List<Map<String, Object>> lines = (List<Map<String, Object>>) b.get("items");
        if (lines == null || lines.isEmpty()) throw err(400, "Agregá al menos un producto.");
        Map<String, Object> sh = openShift(u);
        if (sh == null && !isBoss(u)) throw err(400, "Fichá tu entrada antes de vender.");

        long number = list(c, "sales").size() + 1;
        String ref = "Venta #" + pad(number);
        List<Map<String, Object>> items = new ArrayList<>();
        double total = 0;
        for (Map<String, Object> l : lines) {
            long q = Math.round(dbl(l.get("qty")));
            if (q < 1) throw err(400, "Cantidad inválida.");
            Rec pr = find(c, "products", l.get("productId"));
            Map<String, Object> p = rd(pr);
            long have = num(p.get("stock"));
            if (q > have) throw err(400, "Stock insuficiente de \"" + p.get("title") + "\" (quedan " + have + ").");
            p.put("stock", have - q);
            wr(pr, p);
            double price = dbl(p.get("sellPrice"));
            total += price * q;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", pr.id); it.put("title", str(p.get("title"))); it.put("qty", q); it.put("price", price); it.put("cost", dbl(p.get("buyPrice")));
            items.add(it);
            move(u, pr.id, str(p.get("title")), "venta", q, have, have - q, ref);
        }

        // cliente: registrado (se usa el existente), nuevo (se busca por email y, si no existe, se crea) o consumidor final (sin datos)
        Rec cr = null;
        String clientName = "Consumidor final", clientEmail = "";
        if (ctype.equals("registrado")) {
            cr = find(c, "clients", b.get("clientId"));
            Map<String, Object> cm = rd(cr);
            clientName = str(cm.get("name")); clientEmail = str(cm.get("email")).toLowerCase();
        } else if (ctype.equals("nuevo")) {
            for (Rec r : recs.findByCompanyIdAndKind(c, "clients")) {
                if (em.equalsIgnoreCase(str(rd(r).get("email")))) { cr = r; break; }
            }
            Map<String, Object> cm;
            if (cr == null) {
                cm = new LinkedHashMap<>();
                cm.put("name", fn + " " + ln); cm.put("firstName", fn); cm.put("lastName", ln); cm.put("email", em); cm.put("phone", "");
                cr = mk(c, "clients", cm);
                log(u, "Registró al cliente " + fn + " " + ln + " (desde una venta)");
            } else cm = rd(cr);
            clientName = str(cm.get("name")); clientEmail = em;
        }

        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("number", number);
        sale.put("at", Instant.now().toString());
        sale.put("userId", u.id); sale.put("user", u.name);
        sale.put("counter", sh == null ? null : sh.get("counter"));
        sale.put("shiftId", sh == null ? null : sh.get("id"));
        sale.put("shiftDefId", sh == null ? null : sh.get("shiftDefId"));
        sale.put("shiftName", sh == null ? null : sh.get("shiftName"));
        sale.put("clientId", cr == null ? null : cr.id); sale.put("clientName", clientName); sale.put("clientEmail", clientEmail);
        sale.put("payment", pay); sale.put("items", items); sale.put("total", total);
        Map<String, Object> out = rd(mk(c, "sales", sale));
        log(u, "Registró la venta #" + pad(number) + " por $" + Math.round(total) + " (" + pay + ") a " + clientName);

        // factura por email (PDF adjunto + detalle en el cuerpo), con la misma configuración de Gmail
        String company = cos.findById(c).map(x -> x.name).orElse("");
        boolean canMail = clientEmail.matches(EMAIL); // consumidor final o cliente sin correo: no se envía factura
        if (canMail) try {
            byte[] pdf = TicketPdf.pdf(company, out, tz);
            mailer.send(clientEmail, "Tu factura de " + company + " - Ticket N° " + pad(number), TicketPdf.text(company, out, tz),
                    TicketPdf.html(company, out, tz), "ticket-" + pad(number) + ".pdf", pdf);
        } catch (Exception e) {
            LOG.warn("No se pudo generar la factura: {}", e.getMessage());
        }
        out.put("emailTo", clientEmail);
        out.put("emailStatus", !canMail ? "sin_email" : mailer.configured() ? "enviando" : "sin_configurar");
        if (!isBoss(u)) ((List<Map<String, Object>>) out.get("items")).forEach(i -> i.remove("cost"));
        return out;
    }

    // ---------- compras a proveedores (solo jefe: incluyen costos) ----------
    static BigDecimal money(Object o) {
        try {
            BigDecimal v = new BigDecimal(str(o).replace(',', '.'));
            if (v.signum() < 0 || v.compareTo(new BigDecimal("1000000000")) > 0) throw err(400, "Hay un precio fuera de rango.");
            return v.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) { throw err(400, "Hay un precio que no es un número válido."); }
    }

    static long wholeQty(Object o) {
        double d;
        try { d = dbl(o); } catch (NumberFormatException e) { d = -1; }
        if (d < 1 || d != Math.floor(d) || d > 10_000_000) throw err(400, "Hay una cantidad inválida (tiene que ser un número entero mayor a 0).");
        return (long) d;
    }

    /**
     * Registra una compra y suma el stock EN LA MISMA TRANSACCIÓN: si algo falla no queda la compra sin stock ni stock sin compra.
     * Es idempotente: el navegador manda un requestId único por compra; un reintento o doble clic devuelve la misma compra sin volver a sumar.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/purchases")
    public Map<String, Object> newPurchase(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = boss(s);
        Long c = u.companyId;
        lockCompany(c);
        List<Map<String, Object>> existing = list(c, "purchases");
        String requestId = cut(str(b.get("requestId")), 64);
        if (!requestId.isEmpty())
            for (Map<String, Object> p : existing) if (requestId.equals(str(p.get("requestId")))) return p; // ya registrada
        Rec sup = find(c, "suppliers", b.get("supplierId")); // el proveedor tiene que ser de esta empresa
        String supplierName = str(rd(sup).get("name"));
        LocalDate today = LocalDate.now(ZoneId.of(tz)), day;
        try { day = str(b.get("date")).isEmpty() ? today : LocalDate.parse(str(b.get("date"))); }
        catch (DateTimeParseException e) { throw err(400, "La fecha de la compra no es válida."); }
        if (day.isAfter(today)) throw err(400, "La fecha de la compra no puede ser futura.");
        String invoice = cut(str(b.get("invoice")), 60), notes = cut(str(b.get("notes")), 500);
        if (!invoice.isEmpty())
            for (Map<String, Object> p : existing)
                if (!"ANULADA".equals(p.get("status")) && invoice.equalsIgnoreCase(str(p.get("invoice"))) && num(p.get("supplierId")) == sup.id)
                    throw err(400, "Ya registraste una compra de este proveedor con el comprobante " + invoice + ".");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) b.get("items");
        if (lines == null || lines.isEmpty()) throw err(400, "Agregá al menos un producto a la compra.");
        if (lines.size() > 200) throw err(400, "La compra tiene demasiados productos.");
        long number = existing.size() + 1;
        String ref = "Compra #" + pad(number) + " · " + supplierName + (invoice.isEmpty() ? "" : " · comprobante " + invoice);
        // 1) se valida TODO antes de modificar nada (productos de la empresa, cantidades y precios)
        List<Object[]> valid = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            long q = wholeQty(l.get("qty"));
            BigDecimal unit = money(l.get("unitPrice"));
            valid.add(new Object[]{find(c, "products", l.get("productId")), q, unit, unit.multiply(BigDecimal.valueOf(q)).setScale(2, RoundingMode.HALF_UP)});
        }
        // 2) recién entonces se suma el stock y se arma la compra (todo en una sola transacción)
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] v : valid) {
            Rec pr = (Rec) v[0];
            long q = (Long) v[1];
            BigDecimal unit = (BigDecimal) v[2], sub = (BigDecimal) v[3];
            Map<String, Object> p = rd(pr);
            long have = num(p.get("stock"));
            p.put("stock", have + q);
            wr(pr, p);
            move(u, pr.id, str(p.get("title")), "entrada", q, have, have + q, ref);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("productId", pr.id); it.put("title", str(p.get("title"))); it.put("qty", q); it.put("unitPrice", unit.doubleValue()); it.put("subtotal", sub.doubleValue());
            items.add(it);
            total = total.add(sub);
        }
        Map<String, Object> pu = new LinkedHashMap<>();
        pu.put("number", number); pu.put("date", day.toString()); pu.put("at", Instant.now().toString());
        pu.put("supplierId", sup.id); pu.put("supplierName", supplierName); pu.put("invoice", invoice); pu.put("notes", notes);
        pu.put("items", items); pu.put("total", total.doubleValue()); pu.put("status", "REGISTRADA"); pu.put("requestId", requestId);
        pu.put("userId", u.id); pu.put("user", u.name);
        Map<String, Object> out = rd(mk(c, "purchases", pu));
        log(u, "Registró la compra #" + pad(number) + " a " + supplierName + " por $" + Math.round(total.doubleValue()));
        return out;
    }

    /**
     * Anula una compra y revierte el stock con movimientos de salida. Primero se verifica TODO (productos existentes y stock suficiente);
     * si algo no se puede revertir, no se modifica nada. La compra no se borra: queda ANULADA con quién, cuándo y por qué.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/purchases/{id}/cancel")
    public Map<String, Object> cancelPurchase(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = boss(s);
        Long c = u.companyId;
        lockCompany(c);
        Rec r = find(c, "purchases", id);
        Map<String, Object> pu = rd(r);
        if ("ANULADA".equals(pu.get("status"))) throw err(400, "La compra ya está anulada.");
        String reason = cut(str(b.get("reason")), 300);
        if (reason.isEmpty()) throw err(400, "Indicá el motivo de la anulación.");
        Map<Long, Long> need = new LinkedHashMap<>();
        for (Map<String, Object> it : (List<Map<String, Object>>) pu.get("items")) need.merge(num(it.get("productId")), num(it.get("qty")), Long::sum);
        Map<Long, Rec> prods = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : need.entrySet()) {
            Rec pr = recs.findById(e.getKey()).filter(x -> c.equals(x.companyId) && "products".equals(x.kind)).orElse(null);
            if (pr == null) throw err(400, "No se puede anular: uno de los productos de la compra ya no existe.");
            Map<String, Object> p = rd(pr);
            long have = num(p.get("stock"));
            if (have < e.getValue())
                throw err(400, "No se puede anular: de \"" + p.get("title") + "\" quedan " + have + " unidades y la compra sumó " + e.getValue() + " (el resto ya se vendió o salió).");
            prods.put(e.getKey(), pr);
        }
        String ref = "Anulación de la compra #" + pad(num(pu.get("number"))) + ": " + reason;
        for (Map.Entry<Long, Long> e : need.entrySet()) {
            Rec pr = prods.get(e.getKey());
            Map<String, Object> p = rd(pr);
            long have = num(p.get("stock"));
            p.put("stock", have - e.getValue());
            wr(pr, p);
            move(u, pr.id, str(p.get("title")), "salida", e.getValue(), have, have - e.getValue(), ref);
        }
        pu.put("status", "ANULADA"); pu.put("cancelledAt", Instant.now().toString()); pu.put("cancelledBy", u.name); pu.put("cancelReason", reason);
        wr(r, pu);
        log(u, "Anuló la compra #" + pad(num(pu.get("number"))) + ": " + reason);
        return ok();
    }

    // ---------- fichaje, mostrador y caja ----------
    @PostMapping("/shift/start")
    public Map<String, Object> shiftStart(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        if (openShift(u) != null) throw err(400, "Ya fichaste tu entrada.");
        long counter = Math.round(dbl(b.get("counter")));
        if (counter < 1) throw err(400, "Indicá en qué mostrador estás.");
        Map<String, Object> def = null;
        if (!activeDefs(u.companyId).isEmpty()) { // si el jefe configuró turnos, el empleado tiene que elegir uno
            if (str(b.get("shiftDefId")).isEmpty()) throw err(400, "Elegí tu turno.");
            def = pickDef(u.companyId, b.get("shiftDefId"));
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("userId", u.id); rec.put("user", u.name); rec.put("start", Instant.now().toString()); rec.put("counter", counter);
        if (def != null) stampDef(rec, def);
        mk(u.companyId, "shifts", rec);
        log(u, "Fichó la entrada en el mostrador " + counter + (def != null ? " (turno " + def.get("name") + ")" : ""));
        return ok();
    }

    @PostMapping("/shift/counter")
    public Map<String, Object> shiftCounter(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        Map<String, Object> sh = openShift(u);
        if (sh == null) throw err(400, "No tenés un turno abierto.");
        long counter = Math.round(dbl(b.get("counter")));
        if (counter < 1) throw err(400, "Indicá un número de mostrador válido.");
        Rec r = find(u.companyId, "shifts", sh.get("id"));
        sh.put("counter", counter);
        if (!str(b.get("shiftDefId")).isEmpty()) stampDef(sh, pickDef(u.companyId, b.get("shiftDefId")));
        wr(r, sh);
        log(u, "Cambió al mostrador " + counter + (sh.get("shiftName") != null ? " (turno " + sh.get("shiftName") + ")" : ""));
        return ok();
    }

    /** Fichar salida: cierra el turno y registra el faltante o sobrante que declara el empleado. */
    @PostMapping("/shift/end")
    public Map<String, Object> shiftEnd(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        Map<String, Object> sh = openShift(u);
        if (sh == null) throw err(400, "No tenés un turno abierto.");
        double amt = Math.max(0, dbl(b.get("diffAmount")));
        String t = str(b.get("diffType"));
        double diff = t.equals("sobrante") ? amt : t.equals("faltante") ? -amt : 0;
        Rec r = find(u.companyId, "shifts", sh.get("id"));
        sh.put("end", Instant.now().toString());
        sh.put("diff", diff);
        sh.put("diffNote", str(b.get("note")));
        wr(r, sh);
        log(u, "Fichó la salida (" + (diff == 0 ? "sin diferencia de caja" : (diff > 0 ? "sobrante $" : "faltante $") + Math.round(Math.abs(diff))) + ")");
        return ok();
    }

    static String dif(double x) { return x == 0 ? "sin diferencia" : (x > 0 ? "sobrante $" : "faltante $") + Math.round(Math.abs(x)); }

    /** Corrección de un cierre de caja ya hecho. Solo el jefe (o el administrador global): boss() lo exige en el servidor. */
    @PutMapping("/shift/{id}/diff")
    public Map<String, Object> editShiftDiff(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = boss(s);
        Rec r = find(u.companyId, "shifts", id);
        Map<String, Object> sh = rd(r);
        if (sh.get("end") == null) throw err(400, "El turno todavía está abierto: el empleado tiene que cerrar la caja primero.");
        String t = str(b.get("diffType"));
        if (!Set.of("faltante", "sobrante", "ninguna").contains(t)) throw err(400, "Tipo de diferencia inválido.");
        double amt = Math.max(0, dbl(b.get("diffAmount")));
        double diff = t.equals("sobrante") ? amt : t.equals("faltante") ? -amt : 0;
        double before = dbl(sh.get("diff"));
        sh.putIfAbsent("originalDiff", before);
        sh.put("diff", diff);
        sh.put("diffNote", str(b.get("note")));
        sh.put("editedBy", u.name);
        sh.put("editedAt", Instant.now().toString());
        wr(r, sh);
        log(u, "Corrigió el cierre de caja de " + sh.get("user") + ": de " + dif(before) + " a " + dif(diff));
        return ok();
    }

    // ---------- turnos configurables (solo jefe) ----------
    Map<String, Object> cleanDef(Map<String, Object> b, AppUser u, Long selfId, Map<String, Object> old) {
        String name = cut(str(b.get("name")), 40), from = str(b.get("from")), to = str(b.get("to"));
        if (name.isEmpty()) throw err(400, "Ingresá el nombre del turno.");
        if (!from.matches(HM) || !to.matches(HM)) throw err(400, "Los horarios deben tener formato HH:MM (por ejemplo 08:00).");
        if (from.equals(to)) throw err(400, "El horario de inicio y el de fin no pueden ser iguales.");
        for (Map<String, Object> d : list(u.companyId, "shiftdefs"))
            if (name.equalsIgnoreCase(str(d.get("name"))) && (selfId == null || num(d.get("id")) != selfId)) throw err(400, "Ya existe un turno con ese nombre.");
        Map<String, Object> m = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old);
        m.put("name", name); m.put("from", from); m.put("to", to);
        m.putIfAbsent("active", true);
        return m;
    }

    @PostMapping("/shiftdefs")
    public Map<String, Object> newDef(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = boss(s);
        Map<String, Object> m = cleanDef(b, u, null, null);
        mk(u.companyId, "shiftdefs", m);
        log(u, "Creó el turno " + m.get("name") + " (" + m.get("from") + " a " + m.get("to") + ")");
        return ok();
    }

    @PutMapping("/shiftdefs/{id}")
    public Map<String, Object> editDef(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = boss(s);
        Rec r = find(u.companyId, "shiftdefs", id);
        Map<String, Object> m = cleanDef(b, u, id, rd(r));
        if (b.containsKey("active")) m.put("active", Boolean.TRUE.equals(b.get("active")));
        wr(r, m);
        log(u, "Modificó el turno " + m.get("name") + " (" + m.get("from") + " a " + m.get("to") + ", " + (Boolean.TRUE.equals(m.get("active")) ? "activo" : "desactivado") + ")");
        return ok();
    }

    @DeleteMapping("/shiftdefs/{id}")
    public Map<String, Object> deleteDef(@PathVariable Long id, HttpSession s) {
        AppUser u = boss(s);
        Rec r = find(u.companyId, "shiftdefs", id);
        Map<String, Object> m = rd(r);
        recs.delete(r); // las ventas ya registradas conservan el nombre del turno
        log(u, "Eliminó el turno " + m.get("name"));
        return ok();
    }

    // ---------- suscripción mensual y Mercado Pago ----------
    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static String first(String... v) {
        for (String x : v) if (x != null && !x.isBlank()) return x.trim();
        return "";
    }

    String fmtDate(Instant t) { return DMY.format(t.atZone(ZoneId.of(tz))); }

    /** Estado real de la suscripción: si figura ACTIVA pero ya pasó el vencimiento, es VENCIDA (no depende de ningún proceso programado). */
    String effStatus(Company c) {
        String st = c.subStatus == null ? "PENDIENTE_PAGO" : c.subStatus;
        if ("ACTIVA".equals(st) && (c.subExpires == null || !Instant.now().isBefore(c.subExpires))) return "VENCIDA";
        return st;
    }

    Company syncSub(Company c) {
        String st = effStatus(c);
        if (!st.equals(c.subStatus)) { c.subStatus = st; cos.save(c); }
        return c;
    }

    String blockReason(Company c) {
        if ("VENCIDA".equals(effStatus(c)))
            return "La suscripción de tu empresa venció" + (c.subExpires != null ? " el " + fmtDate(c.subExpires) : "") + ". Renovála para volver a usar la aplicación.";
        return "Tu empresa todavía no tiene una suscripción activa. Para usar la aplicación hay que pagar la suscripción mensual.";
    }

    /** Rechaza (402) cualquier operación normal si la empresa del usuario no tiene la suscripción ACTIVA. */
    void enforce(AppUser u) {
        Company c = cos.findById(u.companyId).orElseThrow(() -> err(401, "Iniciá sesión."));
        if (!"ACTIVA".equals(effStatus(c))) throw err(402, blockReason(c));
    }

    List<Map<String, Object>> paymentRows(Long companyId) {
        return pays.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(p -> p.mpPaymentId != null || !"PENDING".equals(p.status)) // no se muestran órdenes abandonadas
                .map(p -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("id", p.id);
                    o.put("at", (p.paidAt != null ? p.paidAt : p.createdAt).toString());
                    o.put("amount", p.amount.doubleValue());
                    o.put("status", p.status);
                    o.put("source", p.source);
                    o.put("mpPaymentId", p.mpPaymentId);
                    o.put("periodStart", p.periodStart == null ? null : p.periodStart.toString());
                    o.put("periodEnd", p.periodEnd == null ? null : p.periodEnd.toString());
                    return o;
                }).collect(Collectors.toList());
    }

    Map<String, Object> subInfo(Company c, boolean boss) {
        String st = effStatus(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", st);
        m.put("price", price.doubleValue());
        m.put("currency", "ARS");
        m.put("start", c.subStart == null ? null : c.subStart.toString());
        m.put("expires", c.subExpires == null ? null : c.subExpires.toString());
        Long days = null;
        if (c.subExpires != null && st.equals("ACTIVA")) days = (long) Math.ceil(Duration.between(Instant.now(), c.subExpires).toSeconds() / 86400.0);
        m.put("daysLeft", days);
        m.put("warn", boss && days != null && days <= warnDays && !isAutoRenew(c));
        m.put("autoRenew", isAutoRenew(c));
        m.put("cancelRequested", c.subCancelRequestedAt != null);
        m.put("cancelRequestedAt", c.subCancelRequestedAt == null ? null : c.subCancelRequestedAt.toString());
        m.put("deleteAt", c.subCancelRequestedAt != null && c.subExpires != null ? c.subExpires.plusSeconds(deleteGraceDays * 86400L).toString() : null);
        m.put("canCancel", st.equals("ACTIVA") && c.subCancelRequestedAt == null);
        m.put("reason", st.equals("ACTIVA") ? null : blockReason(c));
        m.put("payOnline", mp.configured());
        if (boss) m.put("payments", paymentRows(c.id));
        return m;
    }

    Map<String, Object> blockedBoot(AppUser u, Company c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", pub(u));
        m.put("company", c.name);
        m.put("blocked", true);
        m.put("subscription", subInfo(c, isBoss(u)));
        return m;
    }

    /** Datos iniciales tras login/registro/recarga: sin suscripción activa solo se devuelve la pantalla de pago (ningún dato de la empresa). */
    Map<String, Object> bootFor(AppUser u) {
        if (isGlobal(u)) return globalBoot(u);
        if ("global".equals(u.role)) throw err(403, "Acceso denegado.");
        Company c = syncSub(cos.findById(u.companyId).orElseThrow(() -> err(401, "Iniciá sesión.")));
        if (!"ACTIVA".equals(effStatus(c))) return blockedBoot(u, c);
        return boot(u);
    }

    /** Aplica un pago aprobado: renueva un mes. Si la suscripción sigue vigente se suma al vencimiento actual; si no, desde hoy. */
    void extend(Company c, Payment p, Instant now) {
        boolean active = "ACTIVA".equals(effStatus(c));
        Instant start = active ? c.subExpires : now;
        Instant end = start.atZone(ZoneId.of(tz)).plusMonths(1).toInstant();
        if (!active || c.subStart == null) c.subStart = now;
        c.subStatus = "ACTIVA";
        c.subExpires = end;
        p.status = "APPROVED"; p.paidAt = now; p.periodStart = start; p.periodEnd = end;
        cos.save(c);
        pays.save(p);
    }

    @GetMapping("/public/plan")
    public Map<String, Object> plan() { return Map.of("price", price.doubleValue(), "currency", "ARS", "period", "mes"); }

    boolean isAutoRenew(Company c) { return "authorized".equals(c.mpPreapprovalStatus) && c.subCancelRequestedAt == null; }

    /**
     * Inicia la suscripción con renovación AUTOMÁTICA en Mercado Pago (preapproval): crea la suscripción pendiente y devuelve la URL donde
     * el cliente la autoriza. El importe, la empresa y la referencia los decide SIEMPRE el servidor; lo único que viene del navegador es el
     * email de la cuenta de Mercado Pago del pagador. La empresa NO se activa acá: se activa cuando llega el pago aprobado por webhook.
     */
    @PostMapping("/subscription/checkout")
    public Map<String, Object> checkout(@RequestBody(required = false) Map<String, Object> b, HttpSession s) {
        AppUser u = real(s);
        if (isGlobal(u) || !isBoss(u)) throw err(403, "Solo el jefe de la empresa puede suscribirse.");
        if (!mp.configured()) throw err(503, "El pago online todavía no está disponible. Contactá a soporte.");
        Company c = cos.findWithLockById(u.companyId).orElseThrow(() -> err(401, "Iniciá sesión."));
        if (isAutoRenew(c)) throw err(400, "La renovación automática ya está activa.");
        String payerEmail = str(b == null ? null : b.get("payerEmail")).toLowerCase();
        if (payerEmail.isEmpty()) payerEmail = str(u.email).toLowerCase();
        if (!payerEmail.matches(EMAIL) || payerEmail.length() > 150) throw err(400, "Ingresá el email de tu cuenta de Mercado Pago.");
        String oldId = c.mpPreapprovalId, oldStatus = c.mpPreapprovalStatus;
        String ref = "LG" + c.id + "-" + UUID.randomUUID();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("frequency", 1); rec.put("frequency_type", "months"); rec.put("transaction_amount", price); rec.put("currency_id", "ARS");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", subTitle); body.put("external_reference", ref); body.put("payer_email", payerEmail);
        body.put("auto_recurring", rec); body.put("back_url", baseUrl + "/?pago=ok"); body.put("status", "pending");
        Map<String, Object> pre;
        try { pre = mp.createPreapproval(body); }
        catch (Exception e) {
            LOG.warn("Mercado Pago (crear suscripción): {}", e.getMessage());
            throw err(502, "No se pudo iniciar la suscripción. Probá de nuevo en unos minutos.");
        }
        String url = mp.checkoutUrl(pre), preId = str(pre.get("id"));
        if (url.isEmpty() || preId.isEmpty()) throw err(502, "No se pudo iniciar la suscripción. Probá de nuevo en unos minutos.");
        c.mpPreapprovalId = preId; c.mpSubRef = ref; c.mpSubAmount = price;
        c.mpPreapprovalStatus = str(pre.get("status")).isEmpty() ? "pending" : str(pre.get("status"));
        cos.save(c);
        if (oldId != null && "pending".equals(oldStatus)) { // una suscripción anterior sin autorizar no debe quedar utilizable
            try { mp.cancelPreapproval(oldId); } catch (Exception e) { LOG.warn("No se pudo cancelar la suscripción pendiente anterior: {}", e.getMessage()); }
        }
        audit.record(c.id, c.name, "SUBSCRIPTION_STARTED", "Suscripción pendiente de autorización en Mercado Pago (" + preId + ").");
        return Map.of("url", url);
    }

    /**
     * Baja de la suscripción: cancela la renovación automática con el mecanismo oficial de Mercado Pago. Solo si Mercado Pago confirma la
     * cancelación se registra la baja; si no, no se muestra como exitosa. El acceso sigue hasta el fin del último período pagado.
     */
    @PostMapping("/subscription/cancel")
    public Map<String, Object> cancelSubscription(@RequestBody(required = false) Map<String, Object> b, HttpSession s) {
        AppUser u = real(s);
        if (isGlobal(u) || !isBoss(u)) throw err(403, "Solo el jefe de la empresa puede dar de baja la suscripción.");
        if (b == null || !Boolean.TRUE.equals(b.get("confirm"))) throw err(400, "Falta la confirmación de la baja.");
        Company c = cos.findWithLockById(u.companyId).orElseThrow(() -> err(401, "Iniciá sesión."));
        if (!"ACTIVA".equals(effStatus(c))) throw err(400, "Solo se puede dar de baja una suscripción activa.");
        if (c.subCancelRequestedAt != null) throw err(400, "La baja ya fue solicitada. Tenés acceso hasta el " + fmtDate(c.subExpires) + ".");
        String preId = c.mpPreapprovalId;
        if (preId != null && !"cancelled".equals(c.mpPreapprovalStatus)) {
            String fail = "Mercado Pago no pudo confirmar la cancelación, así que la baja NO fue registrada. Probá de nuevo en unos minutos.";
            if (!mp.configured()) throw err(503, fail);
            Map<String, Object> res;
            try { res = mp.cancelPreapproval(preId); }
            catch (Exception e) {
                LOG.warn("Mercado Pago (cancelar suscripción): {}", e.getMessage());
                audit.record(c.id, c.name, "CANCEL_FAILED", "Mercado Pago no confirmó la cancelación: " + e.getMessage());
                throw err(502, fail);
            }
            if (res == null || !"cancelled".equals(str(res.get("status")))) {
                audit.record(c.id, c.name, "CANCEL_FAILED", "Mercado Pago respondió sin estado 'cancelled'.");
                throw err(502, fail);
            }
            c.mpPreapprovalStatus = "cancelled";
        }
        Instant now = Instant.now();
        c.subCancelRequestedAt = now;
        c.subCancelConfirmedAt = now;
        cos.save(c);
        audit.record(c.id, c.name, "CANCEL_CONFIRMED", "Baja solicitada por " + u.username + ". Acceso hasta " + c.subExpires + ".");
        log(u, "Dio de baja la suscripción (acceso hasta " + fmtDate(c.subExpires) + ")");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("accessUntil", c.subExpires.toString());
        r.put("deleteAt", c.subExpires.plusSeconds(deleteGraceDays * 86400L).toString());
        return r;
    }

    static final Set<String> WEBHOOK_TYPES = Set.of("payment", "subscription_preapproval", "subscription_authorized_payment");

    /**
     * Webhook de Mercado Pago (público, sin sesión). NUNCA confía en el contenido de la notificación: valida la firma (si hay secreto) y
     * consulta el recurso (pago, suscripción o cobro) directamente a Mercado Pago. Si la consulta falla responde 5xx para que reintente.
     */
    @PostMapping("/mercadopago/webhook")
    public ResponseEntity<Map<String, Object>> webhook(@RequestParam Map<String, String> q,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "x-signature", required = false) String sig,
            @RequestHeader(value = "x-request-id", required = false) String reqId) {
        String type = first(q.get("type"), q.get("topic"), body == null ? null : str(body.get("type")));
        Object data = body == null ? null : body.get("data");
        String bodyId = data instanceof Map<?, ?> dm ? str(dm.get("id")) : null;
        String dataId = first(q.get("data.id"), q.get("id"), bodyId);
        if (!WEBHOOK_TYPES.contains(type) || dataId.isEmpty()) return ResponseEntity.ok(ok()); // otros tipos de aviso: se ignoran
        if (!mp.validSignature(sig, reqId, first(q.get("data.id"), q.get("id"))))
            return ResponseEntity.status(401).body(Map.of("message", "Firma inválida."));
        if (!mp.configured()) return ResponseEntity.status(503).body(Map.of("message", "Mercado Pago no está configurado."));
        Map<String, Object> pay = null, pre = null;
        String preId = "";
        try { // solo consultas a Mercado Pago (sin tocar la base)
            if (type.equals("payment")) pay = mp.getPayment(dataId);
            else if (type.equals("subscription_preapproval")) pre = mp.getPreapproval(dataId);
            else {
                Map<String, Object> ap = mp.getAuthorizedPayment(dataId);
                if (ap != null) {
                    preId = str(ap.get("preapproval_id"));
                    Object po = ap.get("payment");
                    String payId = po instanceof Map<?, ?> pm ? str(pm.get("id")) : "";
                    if (!payId.isEmpty()) pay = mp.getPayment(payId);
                }
            }
        } catch (Exception e) {
            LOG.warn("Mercado Pago (consultar {} {}): {}", type, dataId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "No se pudo verificar el aviso."));
        }
        if (pre != null) syncPreapproval(pre);
        if (pay != null) {
            if (preId.isEmpty()) processMpPayment(pay);
            else {
                Long cid = cos.findProjectedByMpPreapprovalId(preId).map(IdRef::getId).orElse(null);
                if (cid != null) applySubscriptionPayment(cid, pay);
                else processSignupPayment(preId, pay);
            }
        }
        return ResponseEntity.ok(ok());
    }

    /** Un pago ya verificado contra Mercado Pago: se asocia a una orden de un solo pago (flujo anterior) o a la suscripción de una empresa. */
    void processMpPayment(Map<String, Object> pay) {
        String ref = str(pay.get("external_reference"));
        if (ref.isEmpty()) return;
        Long orderCompany = pays.findProjectedByReference(ref).map(CompanyRef::getCompanyId).orElse(null);
        if (orderCompany != null) { processOrderPayment(pay, ref, orderCompany); return; }
        Long subCompany = cos.findProjectedByMpSubRef(ref).map(IdRef::getId).orElse(null);
        if (subCompany != null) { applySubscriptionPayment(subCompany, pay); return; }
        SignupIntent intent = signupIntents.findByExternalReference(ref).orElse(null);
        if (intent != null) { processSignupPayment(intent.mpPreapprovalId, pay); return; }
        LOG.warn("Pago de Mercado Pago sin orden ni suscripción conocida ({})", str(pay.get("id")));
    }

    /** Primer pago de una contratación pública. No crea empresa ni usuario: solo habilita el formulario posterior al pago. */
    void processSignupPayment(String preapprovalId, Map<String, Object> pay) {
        SignupIntent in = signupIntents.findByMpPreapprovalId(preapprovalId).orElse(null);
        if (in == null || "COMPLETED".equals(in.status) || "PAID".equals(in.status)) return;
        String paymentId = str(pay.get("id")), mpStatus = str(pay.get("status"));
        if (paymentId.isEmpty()) return;
        if ("approved".equals(mpStatus)) {
            BigDecimal paid = null;
            try { if (pay.get("transaction_amount") != null) paid = new BigDecimal(String.valueOf(pay.get("transaction_amount"))); } catch (NumberFormatException ignored) {}
            if (paid == null || in.amount == null || paid.compareTo(in.amount) != 0 || !"ARS".equals(str(pay.get("currency_id")))) {
                in.status = "REJECTED"; in.mpStatus = "amount_mismatch"; signupIntents.save(in);
                LOG.warn("Primer pago de contratación {} no coincide con el importe/moneda esperados.", in.externalReference); return;
            }
            in.status = "PAID"; in.mpStatus = "authorized"; in.mpPaymentId = paymentId; in.paidAt = Instant.now();
        } else if ("rejected".equals(mpStatus) || "cancelled".equals(mpStatus)) { in.status = "REJECTED"; in.mpStatus = mpStatus; }
        else in.mpStatus = mpStatus;
        signupIntents.save(in);
    }

    /** Estado de la suscripción en Mercado Pago (autorizada, pausada, cancelada...). No activa la empresa: eso solo lo hacen los pagos aprobados. */
    void syncPreapproval(Map<String, Object> pre) {
        String id = str(pre.get("id")), status = str(pre.get("status"));
        Long cid = cos.findProjectedByMpPreapprovalId(id).map(IdRef::getId).orElse(null);
        if (cid == null) {
            SignupIntent intent = signupIntents.findByMpPreapprovalId(id).orElse(null);
            if (intent != null) { intent.mpStatus = status; signupIntents.save(intent); return; }
            LOG.warn("Suscripción de Mercado Pago desconocida ({})", id); return;
        }
        Company c = cos.findWithLockById(cid).orElse(null);
        if (c == null || !id.equals(c.mpPreapprovalId)) return; // no es la suscripción vigente de la empresa
        String before = c.mpPreapprovalStatus;
        c.mpPreapprovalStatus = status;
        if (status.equals("authorized")) { c.subCancelRequestedAt = null; c.subCancelConfirmedAt = null; } // volvió a suscribirse
        cos.save(c);
        if (!status.equals(before)) audit.record(c.id, c.name, "PREAPPROVAL_" + status.toUpperCase(), "Estado en Mercado Pago: " + before + " → " + status);
    }

    /**
     * Un cobro de la suscripción (alta o renovación mensual). Idempotente: el id del pago de Mercado Pago es único, y se bloquea la empresa;
     * si ese pago ya fue aprobado no se vuelve a extender. Solo se renueva con status "approved", moneda ARS y el importe acordado.
     */
    void applySubscriptionPayment(Long companyId, Map<String, Object> pay) {
        String mpId = str(pay.get("id")), mpStatus = str(pay.get("status"));
        if (mpId.isEmpty()) return;
        Company c = cos.findWithLockById(companyId).orElse(null);
        if (c == null) return;
        Payment p = pays.findByMpPaymentId(mpId).orElse(null);
        if (p != null && "APPROVED".equals(p.status)) return; // notificación repetida
        BigDecimal expected = c.mpSubAmount != null ? c.mpSubAmount : price, paid = null;
        try { if (pay.get("transaction_amount") != null) paid = new BigDecimal(String.valueOf(pay.get("transaction_amount"))); }
        catch (NumberFormatException e) { paid = null; }
        boolean amountOk = paid != null && paid.compareTo(expected) == 0 && "ARS".equals(str(pay.get("currency_id")));
        if (p == null) {
            p = new Payment();
            p.companyId = c.id; p.companyName = c.name; p.reference = "SUB-" + mpId; p.mpPaymentId = mpId; p.preapprovalId = c.mpPreapprovalId;
            p.source = "MERCADOPAGO"; p.status = "PENDING"; p.amount = paid != null ? paid : expected; p.currency = "ARS"; p.createdAt = Instant.now();
        }
        p.mpStatus = mpStatus;
        if ("approved".equals(mpStatus)) {
            if (!amountOk) {
                p.status = "REJECTED"; pays.save(p);
                LOG.warn("Pago {} aprobado pero con importe o moneda distintos: no se renueva.", mpId);
                audit.record(c.id, c.name, "PAYMENT_MISMATCH", "El pago " + mpId + " vino aprobado con importe o moneda distintos al acordado: no se renovó.");
                return;
            }
            extend(c, p, Instant.now());
            audit.record(c.id, c.name, "PAYMENT_APPROVED", "Pago " + mpId + " aprobado. Acceso hasta " + c.subExpires + ".");
        } else if (mpStatus.equals("rejected") || mpStatus.equals("cancelled")) {
            p.status = "REJECTED";
            pays.save(p); // la empresa conserva su estado anterior
        } else {
            pays.save(p); // pending / in_process / otros: sigue PENDING
        }
    }

    /** Orden de un solo pago (flujo anterior de Checkout Pro): se mantiene para pagos que ya estuvieran en curso. */
    void processOrderPayment(Map<String, Object> pay, String ref, Long companyId) {
        String mpId = str(pay.get("id")), mpStatus = str(pay.get("status"));
        Company c = cos.findWithLockById(companyId).orElse(null); // bloqueo por empresa
        if (c == null) return;
        Payment p = pays.findByReference(ref).orElse(null); // se lee DESPUÉS de bloquear
        if (p == null || "APPROVED".equals(p.status)) return; // notificación repetida: no se extiende de nuevo
        if (!mpId.isEmpty()) {
            Optional<Payment> dup = pays.findByMpPaymentId(mpId);
            if (dup.isPresent() && !dup.get().id.equals(p.id)) { LOG.warn("El pago {} ya está asociado a otra orden.", mpId); return; }
            p.mpPaymentId = mpId;
        }
        p.mpStatus = mpStatus;
        boolean amountOk = false;
        if (pay.get("transaction_amount") != null) {
            try { amountOk = new BigDecimal(String.valueOf(pay.get("transaction_amount"))).compareTo(p.amount) == 0; }
            catch (NumberFormatException e) { amountOk = false; }
        }
        amountOk = amountOk && "ARS".equals(str(pay.get("currency_id")));
        if ("approved".equals(mpStatus)) {
            if (!amountOk) { p.status = "REJECTED"; pays.save(p); LOG.warn("Pago {} aprobado pero con importe o moneda distintos: no se activa.", mpId); return; }
            extend(c, p, Instant.now());
        } else if (mpStatus.equals("rejected") || mpStatus.equals("cancelled")) {
            p.status = "REJECTED";
            pays.save(p);
        } else {
            pays.save(p);
        }
    }

    /** Ejecuta las tareas automáticas (revocar acceso vencido, eliminar bajas vencidas). Lo llama Cloud Scheduler con un token secreto. */
    @PostMapping("/internal/jobs/run")
    public Map<String, Object> runJobs(@RequestHeader(value = "X-Jobs-Token", required = false) String token) {
        String key = "jobs|" + ip();
        if (tooMany(key, 10, 900)) throw err(429, "Demasiados intentos. Probá más tarde.");
        if (!jobs.tokenOk(token)) { hit(key); throw err(403, "No autorizado."); }
        return jobs.run();
    }

    /** Acciones manuales del administrador global sobre la suscripción (activar/extender, marcar vencida o pendiente). */
    @PostMapping("/admin/companies/{id}/subscription")
    public Map<String, Object> adminSub(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser g = global(s);
        Company c = cos.findWithLockById(id).orElseThrow(() -> err(404, "La empresa no existe."));
        String action = str(b.get("action"));
        Instant now = Instant.now();
        if (action.equals("activate")) {
            Payment p = new Payment();
            p.companyId = c.id; p.companyName = c.name; p.reference = "MANUAL-" + UUID.randomUUID(); p.source = "MANUAL"; p.createdAt = now;
            p.amount = BigDecimal.ZERO; p.currency = "ARS";
            extend(c, p, now);
        } else if (action.equals("expire")) {
            c.subStatus = "VENCIDA"; c.subExpires = now; cos.save(c);
        } else if (action.equals("pending")) {
            c.subStatus = "PENDIENTE_PAGO"; c.subStart = null; c.subExpires = null; cos.save(c);
        } else throw err(400, "Acción inválida.");
        LOG.info("{} aplicó '{}' a la suscripción de {}", g.username, action, c.name);
        return ok();
    }

    // ---------- usuarios de la empresa (jefe) ----------
    AppUser target(AppUser boss, Long id) {
        AppUser t = users.findById(id).orElseThrow(() -> err(404, "No existe."));
        if (t.companyId == null || !boss.companyId.equals(t.companyId)) throw err(404, "No existe.");
        return t;
    }

    @PostMapping("/users")
    public Map<String, Object> newUser(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser j = boss(s);
        String n = str(b.get("name")), un = str(b.get("username")).toLowerCase(), pw = str(b.get("password"));
        String role = "jefe".equals(str(b.get("role"))) ? "jefe" : "empleado"; // nunca se puede crear un "global"
        if (n.isEmpty() || un.isEmpty()) throw err(400, "Completá nombre y usuario.");
        if (pw.length() < MINPW) throw err(400, PWMSG);
        if (users.findByUsername(un).isPresent()) throw err(400, "Ese usuario ya existe. Probá con otro.");
        AppUser u = new AppUser();
        u.companyId = j.companyId; u.name = n; u.username = un; u.passHash = enc.encode(pw); u.role = role;
        users.save(u);
        log(j, "Creó el usuario " + n + " (" + role + ")");
        return ok();
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> editUser(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser j = boss(s);
        AppUser t = target(j, id);
        String n = str(b.get("name")), em = str(b.get("email")).toLowerCase();
        if (n.isEmpty()) throw err(400, "Completá el nombre.");
        if (!em.isEmpty()) {
            if (!em.matches(EMAIL)) throw err(400, "Ingresá un email válido.");
            Optional<AppUser> o = users.findByEmailIgnoreCase(em);
            if (o.isPresent() && !o.get().id.equals(t.id)) throw err(400, "Ese email ya está en uso.");
        }
        t.name = n;
        t.email = em.isEmpty() ? null : em;
        users.save(t);
        log(j, "Editó al usuario " + n);
        return ok();
    }

    @PutMapping("/users/{id}/role")
    public Map<String, Object> toggleRole(@PathVariable Long id, HttpSession s) {
        AppUser j = boss(s);
        AppUser t = target(j, id);
        if (t.id.equals(j.id)) throw err(400, "No podés cambiar tu propio rol.");
        t.role = isBoss(t) ? "empleado" : "jefe";
        users.save(t);
        log(j, "Cambió el rol de " + t.name + " a " + t.role);
        return ok();
    }

    @PostMapping("/users/{id}/password")
    public Map<String, Object> setPassword(@PathVariable Long id, @RequestBody Map<String, Object> b, HttpSession s) {
        AppUser j = boss(s);
        AppUser t = target(j, id);
        String pw = str(b.get("password"));
        if (pw.length() < MINPW) throw err(400, PWMSG);
        t.passHash = enc.encode(pw);
        users.save(t);
        log(j, "Cambió la contraseña de " + t.name);
        return ok();
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id, HttpSession s) {
        AppUser j = boss(s);
        AppUser t = target(j, id);
        if (t.id.equals(j.id)) throw err(400, "No podés eliminar tu propio usuario.");
        users.delete(t);
        log(j, "Eliminó al usuario " + t.name);
        return ok();
    }
}
