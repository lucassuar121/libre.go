package com.librego;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.librego.Models.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Transactional
public class Api {

    static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";
    static final int MINPW = 8;
    static final String PWMSG = "La contraseña debe tener al menos " + MINPW + " caracteres.";
    static final String TOKMSG = "El enlace no es válido o ya venció. Pedí uno nuevo.";

    private final Companies cos;
    private final Users users;
    private final Recs recs;
    private final ObjectMapper om;
    private final Mailer mailer;
    private final HttpServletRequest req;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    private final SecureRandom rnd = new SecureRandom();
    @Value("${app.base-url}") private String baseUrl;
    @Value("${app.global-admin.username}") private String adminUser;
    @Value("${app.reset-minutes:30}") private long resetMinutes;
    @Value("${app.timezone:America/Argentina/Cordoba}") private String tz;

    public Api(Companies cos, Users users, Recs recs, ObjectMapper om, Mailer mailer, HttpServletRequest req) {
        this.cos = cos; this.users = users; this.recs = recs; this.om = om; this.mailer = mailer; this.req = req;
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

    @ExceptionHandler({NumberFormatException.class, NullPointerException.class, ClassCastException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> bad(Exception e) { return Map.of("message", "Datos inválidos."); }

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
        String co = str(b.get("company")), name = str(b.get("name")), un = str(b.get("username")).toLowerCase();
        String em = str(b.get("email")).toLowerCase(), pw = str(b.get("password"));
        if (co.isEmpty() || name.isEmpty() || un.isEmpty()) throw err(400, "Completá todos los campos.");
        if (!em.matches(EMAIL)) throw err(400, "Ingresá un email válido.");
        if (pw.length() < MINPW) throw err(400, PWMSG);
        if (users.findByUsername(un).isPresent()) throw err(400, "Ese usuario ya existe. Probá con otro.");
        if (users.findByEmailIgnoreCase(em).isPresent()) throw err(400, "Ese email ya tiene una cuenta.");
        Company c = new Company();
        c.name = co;
        c = cos.save(c);
        AppUser u = new AppUser();
        u.companyId = c.id; u.name = name; u.username = un; u.email = em; u.passHash = enc.encode(pw); u.role = "jefe";
        u = users.save(u);
        s.setAttribute("uid", u.id);
        log(u, "Creó la cuenta de la empresa");
        return boot(u);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = users.findByUsername(str(b.get("username")).toLowerCase()).orElse(null);
        if (u == null || !enc.matches(str(b.get("password")), u.passHash)) throw err(400, "Usuario o contraseña incorrectos.");
        s.setAttribute("uid", u.id);
        if (isGlobal(u)) return globalBoot(u);
        if ("global".equals(u.role)) throw err(403, "Acceso denegado.");
        log(u, "Inició sesión");
        return boot(u);
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
        if (isGlobal(r) && (h == null || h.isBlank())) return globalBoot(r);
        Map<String, Object> m = boot(me(s));
        if (isGlobal(r)) m.put("gadmin", true);
        return m;
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
            cs.add(o);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", pub(u));
        m.put("companies", cs);
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
        if (!boss) shifts = shifts.stream().filter(x -> num(x.get("userId")) == u.id).collect(Collectors.toList());
        addCash(shifts, sales);
        List<Map<String, Object>> prods = list(c, "products");
        if (!boss) { // el empleado no ve costos ni ganancias
            prods.forEach(p -> p.remove("buyPrice"));
            sales.forEach(x -> ((List<Map<String, Object>>) x.get("items")).forEach(i -> i.remove("cost")));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("me", pub(u));
        m.put("company", cos.findById(c).map(x -> x.name).orElse(""));
        m.put("products", prods);
        m.put("clients", list(c, "clients"));
        m.put("suppliers", list(c, "suppliers"));
        m.put("sales", sales);
        m.put("moves", list(c, "moves"));
        m.put("shifts", shifts);
        m.put("openShift", shifts.stream().filter(x -> x.get("end") == null && num(x.get("userId")) == u.id).findFirst().orElse(null));
        m.put("users", new ArrayList<>());
        m.put("log", new ArrayList<>());
        if (boss) {
            m.put("users", users.findByCompanyId(c).stream().map(this::pub).collect(Collectors.toList()));
            m.put("log", list(c, "log").stream().sorted((x, y) -> Long.compare(num(y.get("id")), num(x.get("id")))).limit(300).collect(Collectors.toList()));
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
        long units = Math.round(dbl(b.get("units")));
        if (units < 1) throw err(400, "Indicá cuántas unidades entraron.");
        b.put("stock", units);
        Map<String, Object> m = clean("products", b, u, null);
        Rec r = mk(u.companyId, "products", m);
        move(u, r.id, str(m.get("title")), "entrada", units, 0, units, "Ingreso de producto nuevo");
        log(u, "Ingresó producto nuevo: " + m.get("title") + " (" + units + " u.)");
        return ok();
    }

    @PostMapping("/stock/move")
    public Map<String, Object> stockMove(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        Rec r = find(u.companyId, "products", b.get("productId"));
        Map<String, Object> p = rd(r);
        String type = str(b.get("type"));
        if (!Set.of("entrada", "salida", "ajuste", "devolucion").contains(type)) throw err(400, "Tipo de movimiento inválido.");
        String reason = str(b.get("reason"));
        if ((type.equals("ajuste") || type.equals("salida")) && reason.isEmpty()) throw err(400, "Indicá el motivo del movimiento.");
        if (reason.isEmpty()) reason = type.equals("entrada") ? "Entrada de mercadería" : "Devolución de cliente";
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
            System.out.println("[Libre.go] No se pudo generar la factura: " + e.getMessage());
        }
        out.put("emailTo", clientEmail);
        out.put("emailStatus", !canMail ? "sin_email" : mailer.configured() ? "enviando" : "sin_configurar");
        if (!isBoss(u)) ((List<Map<String, Object>>) out.get("items")).forEach(i -> i.remove("cost"));
        return out;
    }

    // ---------- fichaje, mostrador y caja ----------
    @PostMapping("/shift/start")
    public Map<String, Object> shiftStart(@RequestBody Map<String, Object> b, HttpSession s) {
        AppUser u = me(s);
        if (openShift(u) != null) throw err(400, "Ya fichaste tu entrada.");
        long counter = Math.round(dbl(b.get("counter")));
        if (counter < 1) throw err(400, "Indicá en qué mostrador estás.");
        mk(u.companyId, "shifts", Map.of("userId", u.id, "user", u.name, "start", Instant.now().toString(), "counter", counter));
        log(u, "Fichó la entrada en el mostrador " + counter);
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
        wr(r, sh);
        log(u, "Cambió al mostrador " + counter);
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
