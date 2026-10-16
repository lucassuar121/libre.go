package com.librego;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.librego.Models.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Crea y mantiene una empresa de muestra aislada, con datos ficticios coherentes para recorrer todos los módulos. */
@Component
public class DemoSeeder implements ApplicationRunner {
    private static final int DEMO_VERSION = 3;
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Cordoba");
    @Value("${demo.boss.username:demo_jefe}") private String bossUsername;
    @Value("${demo.boss.password:DemoJefe2026!}") private String bossPassword;
    @Value("${demo.employee.username:demo_empleado}") private String employeeUsername;
    @Value("${demo.employee.password:DemoEmpleado2026!}") private String employeePassword;

    private final Environment environment;
    private final Companies companies;
    private final Users users;
    private final Recs recs;
    private final ObjectMapper mapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DemoSeeder(Companies companies, Users users, Recs recs, ObjectMapper mapper, Environment environment) {
        this.companies = companies; this.users = users; this.recs = recs; this.mapper = mapper; this.environment = environment;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (environment.acceptsProfiles(Profiles.of("prod")) &&
                (bossPassword == null || bossPassword.isBlank() || bossPassword.equals("DemoJefe2026!") ||
                 employeePassword == null || employeePassword.isBlank() || employeePassword.equals("DemoEmpleado2026!"))) {
            throw new IllegalStateException("[Libre.go] En producción configurá DEMO_BOSS_PASSWORD y DEMO_EMPLOYEE_PASSWORD con claves privadas antes de iniciar la aplicación.");
        }
        Company demo = companies.findAll().stream().filter(c -> Boolean.TRUE.equals(c.demo)).findFirst().orElse(null);
        if (demo == null) {
            demo = new Company();
            demo.name = "Librería Horizonte · DEMO";
            demo.demo = true;
            demo.demoVersion = DEMO_VERSION;
            demo.subStatus = "ACTIVA";
            demo.subStart = Instant.now();
            demo.subExpires = Instant.now().plus(3650, ChronoUnit.DAYS);
            demo = companies.save(demo);
            List<AppUser> team = ensureUsers(demo.id);
            seedRecords(demo.id, team);
            return;
        }
        List<AppUser> team = ensureUsers(demo.id);
        if (demo.demoVersion == null || demo.demoVersion < DEMO_VERSION) {
            recs.deleteByCompanyId(demo.id);
            seedRecords(demo.id, team);
            demo.demoVersion = DEMO_VERSION;
            companies.save(demo);
        }
    }

    private List<AppUser> ensureUsers(Long cid) {
        List<AppUser> team = users.findByCompanyId(cid);
        AppUser boss = team.stream().filter(u -> "jefe".equals(u.role)).findFirst().orElseGet(() -> {
            AppUser u = new AppUser(); u.companyId = cid; u.role = "jefe"; return u;
        });
        boss.name = "María González"; boss.username = reserveUsername(bossUsername, cid, boss.id);
        boss.email = "jefe@demo.librego.invalid"; boss.passHash = encoder.encode(bossPassword); boss.role = "jefe";
        boss = users.save(boss);

        AppUser employee = team.stream().filter(u -> "empleado".equals(u.role)).findFirst().orElseGet(() -> {
            AppUser u = new AppUser(); u.companyId = cid; u.role = "empleado"; return u;
        });
        employee.name = "Tomás Pérez"; employee.username = reserveUsername(employeeUsername, cid, employee.id);
        employee.email = "empleado@demo.librego.invalid"; employee.passHash = encoder.encode(employeePassword); employee.role = "empleado";
        employee = users.save(employee);

        ensureEmployee(cid, "demo_empleado2", "Lucía Fernández", "empleado2@demo.librego.invalid");
        ensureEmployee(cid, "demo_empleado3", "Mateo Romero", "empleado3@demo.librego.invalid");
        ensureEmployee(cid, "demo_empleado4", "Camila Torres", "empleado4@demo.librego.invalid");
        return users.findByCompanyId(cid);
    }

    private void ensureEmployee(Long cid, String username, String name, String email) {
        AppUser u = users.findByUsername(username).filter(x -> Objects.equals(x.companyId, cid)).orElseGet(() -> {
            AppUser n = new AppUser(); n.companyId = cid; n.role = "empleado"; return n;
        });
        u.companyId = cid; u.name = name; u.username = reserveUsername(username, cid, u.id);
        u.email = email; u.passHash = encoder.encode(employeePassword); u.role = "empleado";
        users.save(u);
    }

    private String reserveUsername(String preferred, Long cid, Long currentId) {
        Optional<AppUser> other = users.findByUsername(preferred);
        if (other.isEmpty() || Objects.equals(other.get().companyId, cid) || Objects.equals(other.get().id, currentId)) return preferred;
        String candidate = preferred + "_demo";
        int i = 2;
        while (users.findByUsername(candidate).filter(x -> !Objects.equals(x.companyId, cid) && !Objects.equals(x.id, currentId)).isPresent()) candidate = preferred + "_demo" + i++;
        return candidate;
    }

    @Transactional
    public void reset(Long companyId) {
        Company c = companies.findById(companyId).orElseThrow();
        if (!Boolean.TRUE.equals(c.demo)) throw new IllegalArgumentException("Solo se puede restaurar la demostración.");
        List<AppUser> team = ensureUsers(companyId);
        recs.deleteByCompanyId(companyId);
        seedRecords(companyId, team);
        c.demoVersion = DEMO_VERSION;
        companies.save(c);
    }

    private void seedRecords(Long cid, List<AppUser> team) {
        AppUser boss = team.stream().filter(u -> "jefe".equals(u.role)).findFirst().orElseThrow();
        List<AppUser> employees = team.stream().filter(u -> "empleado".equals(u.role)).sorted(Comparator.comparing((AppUser u) -> u.username)).toList();
        if (employees.isEmpty()) throw new IllegalStateException("La demostración necesita al menos un empleado.");

        List<Map<String,Object>> productData = new ArrayList<>();
        String[][] products = {
            {"El jardín de los senderos", "Jorge Luis Borges", "Literatura", "9789500712345", "4500", "7900"},
            {"Cuentos para la tarde", "Mariana Ruiz", "Infantil", "9789500712346", "3200", "5900"},
            {"Aprender Java desde cero", "S. Fernández", "Informática", "9789500712347", "9000", "14500"},
            {"Cuaderno tapa dura", "", "Librería", "CUA-001", "1800", "3200"},
            {"Marcadores x6", "", "Librería", "MAR-006", "2100", "3900"},
            {"El principito", "Antoine de Saint-Exupéry", "Clásicos", "9789500756789", "5200", "8900"},
            {"Matemática en acción 1", "Equipo Editorial", "Escolar", "9789500123456", "6100", "9800"},
            {"Historias de Córdoba", "Paula Ríos", "Historia", "9789500678912", "7400", "11900"},
            {"Agenda anual", "", "Librería", "AGE-2026", "3500", "6200"},
            {"Lapiceras azules x10", "", "Librería", "LAP-AZ10", "2300", "4100"},
            {"Introducción a Python", "N. Acosta", "Informática", "9789500987654", "8200", "13200"},
            {"Misterios del bosque", "Laura Méndez", "Infantil", "9789500111122", "4100", "6900"},
            {"Biología para todos", "M. Pereyra", "Escolar", "9789500222233", "6800", "10500"},
            {"Carpeta A4", "", "Librería", "CAR-A4", "1700", "2900"},
            {"Resaltadores pastel x4", "", "Librería", "RES-P4", "2600", "4500"},
            {"Historia argentina breve", "F. Molina", "Historia", "9789500333344", "5700", "9200"},
            {"Ciencias naturales 2", "Equipo Editorial", "Escolar", "9789500444455", "6300", "9900"},
            {"Novela de invierno", "Carla Suárez", "Novela", "9789500555566", "7100", "11500"},
            {"Block de dibujo", "", "Librería", "BLO-DIB", "1900", "3400"},
            {"Diccionario escolar", "Varios autores", "Escolar", "9789500666677", "8600", "13900"},
            {"Goma blanca x2", "", "Librería", "GOM-B2", "700", "1400"},
            {"Física explicada", "R. Núñez", "Ciencia", "9789500777788", "9300", "15100"},
            {"Témperas x6", "", "Librería", "TEM-006", "2400", "4300"},
            {"Lecturas para compartir", "Ana Beltrán", "Infantil", "9789500888899", "3900", "6500"}
        };

        List<Map<String,Object>> clients = new ArrayList<>();
        String[] clientNames = {"Sofía Martínez","Diego Fernández","Valentina López","Martín Castro","Julieta Herrera","Bruno Gómez","Camila Díaz","Nicolás Romero","Agustina Molina","Facundo Torres","Milagros Acosta","Pedro Sosa"};
        for (int i=0;i<clientNames.length;i++) clients.add(map("name",clientNames[i],"email","cliente"+(i+1)+"@example.invalid","phone","351-555-"+String.format("%04d",101+i),"notes",i%3==0?"Cliente frecuente (datos ficticios)":""));
        List<Long> clientIds = new ArrayList<>(); for (Map<String,Object> c:clients) clientIds.add(save(cid,"clients",c));

        List<Map<String,Object>> suppliers = new ArrayList<>();
        String[][] sup = {{"Distribuidora Central","Ana Torres"},{"Editorial del Centro","Pablo Díaz"},{"Mayorista Escolar Córdoba","Micaela Luna"},{"Libros del Parque","Sergio Gil"},{"Papelera Norte","Romina Paz"},{"Ediciones Horizonte","Federico Rey"},{"Comercial San Martín","Laura Vera"},{"Distribuciones Sierras","Andrés Ortiz"}};
        List<Long> supplierIds = new ArrayList<>();
        for(int i=0;i<sup.length;i++) supplierIds.add(save(cid,"suppliers",map("name",sup[i][0]+" (demo)","contact",sup[i][1],"phone","351-555-"+String.format("%04d",201+i),"email","ventas"+(i+1)+"@proveedor.example.invalid","website","https://example.invalid","notes","Proveedor ficticio para demostración")));

        // La demo tiene dos mostradores operativos, con fondos iniciales independientes.
        // Esto permite que dos empleados trabajen en paralelo sin compartir una caja abierta.
        save(cid,"companyconfig",map("counters",2,"cashBalances",List.of(25000.0,18000.0)));

        long morningId = save(cid,"shiftdefs",map("name","Mañana","from","08:00","to","14:00","active",true));
        long eveningId = save(cid,"shiftdefs",map("name","Tarde/Noche","from","14:00","to","22:00","active",true));

        // Historial de ventas distribuido entre fechas, turnos, empleados y ambos mostradores.
        int[][] specs = {
            {0,14,0,1,1,0,0,0,2},{0,15,1,2,1,1,1,1,1},{0,16,1,2,1,2,3,2,3},
            {1,10,0,1,0,3,2,3,1},{1,11,2,2,0,4,4,4,2},{1,17,3,1,1,5,5,5,1},
            {2,9,1,1,0,6,6,6,1},{2,18,2,2,1,7,7,7,2},
            {3,10,3,2,0,8,8,8,1},{3,16,0,1,1,9,9,9,3},
            {5,11,2,1,0,10,10,10,1},{5,19,1,2,1,11,11,11,2},
            {7,9,0,2,0,0,0,12,1},{7,15,3,1,1,1,1,13,2},
            {12,12,1,1,0,2,2,14,1},{12,18,2,2,1,3,3,15,2},
            {20,10,3,2,0,4,4,16,1},{20,17,0,1,1,5,5,17,2},
            {28,9,2,1,0,6,6,18,1},{28,16,1,2,1,7,7,19,1},
            {35,11,0,1,0,8,8,20,2},{35,18,3,2,1,9,9,21,1},
            {45,10,1,2,0,10,10,22,1},{45,17,2,1,1,11,11,23,2}
        };
        long[] soldQty = new long[products.length];
        long[] boughtQty = new long[products.length];
        long[] initialStocks = new long[products.length];
        for (int[] s:specs) soldQty[s[7]] += s[8];
        int[][] purchases = {{5,0,18},{12,3,30},{20,8,15},{27,9,24},{40,2,12}};
        for (int[] p:purchases) boughtQty[p[1]] += p[2];
        List<Long> productIds = new ArrayList<>();
        for(int i=0;i<products.length;i++) {
            long initial = 20 + (i*7)%24;
            initialStocks[i] = initial;
            long stock = Math.max(0, initial + boughtQty[i] - soldQty[i]);
            productIds.add(save(cid,"products",map("type",i==3||i==4||i>=8&&i%2==0?"otro":"libro","title",products[i][0],"author",products[i][1],"isbn",products[i][3],"category",products[i][2],"buyPrice",Double.parseDouble(products[i][4]),"sellPrice",Double.parseDouble(products[i][5]),"ivaRate",21.0,"stock",stock)));
        }

        Instant now = Instant.now();
        Map<String,Long> shiftCache = new HashMap<>();
        List<Map<String,Object>> saleRows = new ArrayList<>();
        List<Map<String,Object>> movementRows = new ArrayList<>();
        int number=1;
        for (int[] s:specs) {
            LocalDate date = LocalDate.now(ZONE).minusDays(s[0]);
            AppUser user = s[2]==0 ? boss : employees.get((s[2]-1)%employees.size());
            int hour=s[1], counter=s[3], turn=s[4]; long defId=turn==0?morningId:eveningId;
            String shiftName=turn==0?"Mañana":"Tarde/Noche";
            String key=date+"|"+user.id+"|"+counter+"|"+turn;
            Long shiftId=shiftCache.get(key);
            if(shiftId==null) {
                ZonedDateTime startZ=date.atTime(turn==0?8:14,0).atZone(ZONE);
                boolean isToday=s[0]==0;
                Instant start=isToday?now.minus(2,ChronoUnit.HOURS):startZ.toInstant();
                Instant end=isToday?null:startZ.plusHours(turn==0?6:8).toInstant();
                Map<String,Object> sh=map("start",start.toString(),"end",end==null?null:end.toString(),"userId",user.id,"user",user.name,"counter",counter,"shiftDefId",defId,"shiftName",shiftName,"shiftFrom",turn==0?"08:00":"14:00","shiftTo",turn==0?"14:00":"22:00","diff",0,"diffNote","Cierre de ejemplo");
                shiftId=save(cid,"shifts",sh); shiftCache.put(key,shiftId);
            }
            Instant at=s[0]==0 ? now.minus((4-number)*25L,ChronoUnit.MINUTES) : date.atTime(hour,10+(number%4)*10).atZone(ZONE).toInstant();
            int pidx=s[7], qty=s[8]; double price=Double.parseDouble(products[pidx][5]), cost=Double.parseDouble(products[pidx][4]);
            String payment=new String[]{"efectivo","tarjeta","qr"}[number%3];
            String clientName=clientNames[s[5]%clientNames.length];
            Long clientId=clientIds.get(s[5]%clientIds.size());
            Map<String,Object> item=map("productId",productIds.get(pidx),"title",products[pidx][0],"qty",qty,"price",price,"cost",cost);
            Map<String,Object> sale=map("number",number,"at",at.toString(),"userId",user.id,"user",user.name,"counter",counter,"shiftId",shiftId,"shiftDefId",defId,"shiftName",shiftName,"clientId",clientId,"clientName",clientName,"clientEmail","","payment",payment,"items",List.of(item),"total",qty*price);
            long saleId=save(cid,"sales",sale);
            sale.put("id",saleId); saleRows.add(sale);
            movementRows.add(map("at",at.plusSeconds(30).toString(),"productIndex",pidx,"productId",productIds.get(pidx),"title",products[pidx][0],"type","venta","qty",qty,"delta",-qty,"reason","Venta #"+String.format("%05d",number),"userId",user.id,"user",user.name));
            number++;
        }

        for(int i=0;i<purchases.length;i++) {
            int[] p=purchases[i]; LocalDate date=LocalDate.now(ZONE).minusDays(p[0]);
            AppUser user=employees.get(i%employees.size()); long prodIdx=p[1], qty=p[2];
            double buy=Double.parseDouble(products[(int)prodIdx][4]);
            String supplierName=sup[(i+1)%sup.length][0]+" (demo)";
            Instant at=date.atTime(9+i%4,15).atZone(ZONE).toInstant();
            save(cid,"purchases",map("number",i+1,"date",date.toString(),"at",at.toString(),"supplierId",supplierIds.get((i+1)%supplierIds.size()),"supplierName",supplierName,"invoice","DEMO-"+String.format("%04d",i+1),"notes","Compra ficticia para mostrar el módulo.","items",List.of(map("productId",productIds.get((int)prodIdx),"title",products[(int)prodIdx][0],"qty",qty,"unitPrice",buy,"subtotal",qty*buy)),"total",qty*buy,"status","REGISTRADA","paymentMethod",(i%3==0?"efectivo":i%3==1?"transferencia":"pendiente"),"paymentStatus",(i%3==2?"PENDIENTE":"PAGADA"),"paidAt",(i%3==2?null:at.toString()),"requestId","demo-purchase-"+(i+1),"userId",boss.id,"user",boss.name));
            movementRows.add(map("at",at.toString(),"productIndex",prodIdx,"productId",productIds.get((int)prodIdx),"title",products[(int)prodIdx][0],"type","entrada","qty",qty,"delta",qty,"reason","Compra a "+supplierName,"userId",boss.id,"user",boss.name));
        }
        movementRows.sort(Comparator.comparing((Map<String,Object> m) -> Instant.parse(String.valueOf(m.get("at")))));
        long[] runningStock = Arrays.copyOf(initialStocks, initialStocks.length);
        for (Map<String,Object> move:movementRows) {
            int pi=(int)((Number)move.get("productIndex")).longValue();
            long before=runningStock[pi], after=before+((Number)move.get("delta")).longValue();
            move.put("before",before); move.put("after",after);
            move.remove("productIndex"); move.remove("delta");
            save(cid,"moves",move);
            runningStock[pi]=after;
        }

        // Bitácora abundante y cronológica: muestra operaciones de distintos usuarios y días.
        String[] events={"Inició sesión","Consultó el panel principal","Registró una venta de ejemplo","Consultó el stock de productos","Registró una compra ficticia","Consultó el historial de caja","Buscó un cliente","Revisó los movimientos de stock","Consultó las estadísticas del negocio","Fichó la entrada en el mostrador","Imprimió un ticket de ejemplo","Consultó el listado de proveedores"};
        for(int i=45;i>=0;i--) {
            AppUser actor=(i%5==0)?boss:employees.get(i%employees.size());
            Instant at=now.minus(i,ChronoUnit.HOURS);
            String text=events[(i*7)%events.length];
            if(text.contains("mostrador")) text += " "+(i%2+1)+" (turno "+(i%2==0?"Mañana":"Tarde/Noche")+")";
            save(cid,"log",map("at",at.toString(),"userId",actor.id,"user",actor.name,"text",text+" · registro ficticio de demostración"));
        }
    }

    private long save(Long cid,String kind,Map<String,Object> data) {
        Rec r=new Rec(); r.companyId=cid; r.kind=kind;
        try { r.payload=mapper.writeValueAsString(data); } catch(Exception e) { throw new IllegalStateException(e); }
        return recs.save(r).id;
    }
    private static Map<String,Object> map(Object... kv) { Map<String,Object> m=new LinkedHashMap<>(); for(int i=0;i<kv.length;i+=2)m.put(String.valueOf(kv[i]),kv[i+1]); return m; }
}
