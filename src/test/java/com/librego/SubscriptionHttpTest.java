package com.librego;

import com.librego.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Flujo HTTP real contra la aplicación: bloqueo por suscripción, permisos y administrador global. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:libregotest2;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.global-admin.password=clave-solo-para-tests-123",
        "mercadopago.access-token=",
        "mercadopago.webhook-secret=",
        "app.cors.allowed-origins="})
@AutoConfigureMockMvc
class SubscriptionHttpTest {
    @Autowired MockMvc mvc;
    @Autowired Companies companies;
    @Autowired Users users;
    @Autowired Recs recs;

    String llamar(MockHttpSession s, String url, String body, int status) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body).session(s))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }


    AppUser crearCuentaDePrueba(MockHttpSession session, String companyName, String name, String username, String email) {
        Company c = new Company(); c.name = companyName; c.subStatus = "PENDIENTE_PAGO"; c = companies.save(c);
        AppUser u = new AppUser(); u.companyId = c.id; u.name = name; u.username = username; u.email = email;
        u.role = "jefe"; u.passHash = new BCryptPasswordEncoder().encode("clave12345"); u = users.save(u);
        session.setAttribute("uid", u.id);
        return u;
    }

    @Test
    void bloqueoPorSuscripcionPermisosYAdministradorGlobal() throws Exception {
        MockHttpSession jefe = new MockHttpSession(), empleado = new MockHttpSession(), admin = new MockHttpSession();

        // El registro directo queda bloqueado: no se crea ninguna empresa ni usuario antes del pago.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"ACME\",\"name\":\"Jefe\",\"username\":\"jefetest\",\"email\":\"jefe@test.com\",\"password\":\"clave12345\"}"))
                .andExpect(status().isForbidden());
        AppUser jefeUser = crearCuentaDePrueba(jefe, "ACME", "Jefe", "jefetest", "jefe@test.com");
        String r = leer(jefe, "/api/bootstrap");
        assertTrue(r.contains("\"blocked\":true"));
        assertTrue(r.contains("PENDIENTE_PAGO"));
        assertFalse(r.contains("\"products\""));
        llamar(jefe, "/api/clients", "{\"name\":\"X\"}", 402); // llamando directo a la API también se rechaza
        mvc.perform(get("/api/bootstrap").session(jefe)).andExpect(status().isOk());

        // empleado de esa empresa: bloqueado
        Long cid = jefeUser.companyId;
        AppUser e = new AppUser();
        e.companyId = cid; e.name = "Ana"; e.username = "anatest"; e.role = "empleado"; e.passHash = new BCryptPasswordEncoder().encode("clave12345");
        users.save(e);
        assertTrue(llamar(empleado, "/api/auth/login", "{\"username\":\"anatest\",\"password\":\"clave12345\"}", 200).contains("\"blocked\":true"));
        llamar(empleado, "/api/clients", "{\"name\":\"X\"}", 402);

        // un jefe no puede activar suscripciones ni borrar empresas
        llamar(jefe, "/api/admin/companies/" + cid + "/subscription", "{\"action\":\"activate\"}", 403);
        mvc.perform(delete("/api/admin/companies/" + cid).contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}").session(jefe)).andExpect(status().is(403));

        // el administrador global ve las empresas y puede activar
        assertTrue(llamar(admin, "/api/auth/login", "{\"username\":\"lucas1\",\"password\":\"clave-solo-para-tests-123\"}", 200).contains("companies"));
        llamar(admin, "/api/admin/companies/" + cid + "/subscription", "{\"action\":\"activate\"}", 200);
        llamar(jefe, "/api/clients", "{\"name\":\"X\"}", 200);
        llamar(empleado, "/api/clients", "{\"name\":\"Y\"}", 200);

        // vencida: se bloquea de nuevo; el administrador global igual puede operar con el encabezado de empresa
        llamar(admin, "/api/admin/companies/" + cid + "/subscription", "{\"action\":\"expire\"}", 200);
        llamar(jefe, "/api/clients", "{\"name\":\"Z\"}", 402);
        mvc.perform(post("/api/clients").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"G\"}").session(admin).header("X-Company", String.valueOf(cid)))
                .andExpect(status().isOk());

        // sin Mercado Pago configurado: el webhook y el pago no activan nada
        mvc.perform(post("/api/mercadopago/webhook?type=payment&data.id=123")).andExpect(status().is(503));
        llamar(jefe, "/api/subscription/checkout", "{}", 503);
        assertEquals("VENCIDA", companies.findById(cid).get().subStatus);
    }

    String leer(MockHttpSession s, String url) throws Exception {
        return mvc.perform(get(url).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void elEmpleadoPuedeRegistrarComprasPeroNoEditarProductosYLasTareasExigenToken() throws Exception {
        MockHttpSession jefe = new MockHttpSession(), empleado = new MockHttpSession(), admin = new MockHttpSession();
        AppUser jefeUser = crearCuentaDePrueba(jefe, "Compras SA", "Jefe Dos", "jefecompras", "compras@test.com");
        Long cid = jefeUser.companyId;
        llamar(admin, "/api/auth/login", "{\"username\":\"lucas1\",\"password\":\"clave-solo-para-tests-123\"}", 200);
        llamar(admin, "/api/admin/companies/" + cid + "/subscription", "{\"action\":\"activate\"}", 200);
        AppUser e = new AppUser();
        e.companyId = cid; e.name = "Ana"; e.username = "anacompras"; e.role = "empleado"; e.passHash = new BCryptPasswordEncoder().encode("clave12345");
        users.save(e);
        llamar(jefe, "/api/suppliers", "{\"name\":\"Proveedor Uno\"}", 200);
        llamar(jefe, "/api/stock/new", "{\"title\":\"Lapiz\",\"type\":\"otro\",\"sellPrice\":500,\"buyPrice\":300,\"units\":5}", 200);
        String boot = leer(jefe, "/api/bootstrap");
        assertTrue(boot.contains("Proveedor Uno") && boot.contains("\"moves\":[{"));
        Long supplier = recs.findByCompanyIdAndKind(cid, "suppliers").get(0).id;
        Long product = recs.findByCompanyIdAndKind(cid, "products").get(0).id;
        String compra = llamar(jefe, "/api/purchases", "{\"requestId\":\"http-1\",\"supplierId\":" + supplier + ",\"invoice\":\"F-1\",\"items\":[{\"productId\":" + product + ",\"qty\":10,\"unitPrice\":250.5}]}", 200);
        assertTrue(compra.contains("\"total\":2505.0"));
        llamar(jefe, "/api/purchases", "{\"requestId\":\"http-1\",\"supplierId\":" + supplier + ",\"invoice\":\"F-1\",\"items\":[{\"productId\":" + product + ",\"qty\":10,\"unitPrice\":250.5}]}", 200);
        assertTrue(leer(jefe, "/api/bootstrap").contains("\"stock\":15")); // el reintento no vuelve a sumar

        // El empleado puede registrar compras y movimientos, pero no editar productos ni consultar el historial global de stock.
        llamar(empleado, "/api/auth/login", "{\"username\":\"anacompras\",\"password\":\"clave12345\"}", 200);
        String bootE = leer(empleado, "/api/bootstrap");
        assertTrue(bootE.contains("\"moves\":[]"));
        assertTrue(bootE.contains("\"purchases\":[{"));
        assertTrue(bootE.contains("\"stock\":15"));
        assertTrue(bootE.contains("\"buyPrice\":250.5")); // la compra recuerda el último costo unitario
        mvc.perform(put("/api/products/" + product).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Cambio no permitido\"}").session(empleado)).andExpect(status().is(403));
        llamar(empleado, "/api/stock/new", "{\"title\":\"Producto cargado por empleado\",\"type\":\"papeleria\",\"sellPrice\":150,\"buyPrice\":90,\"units\":1}", 200);
        String bootConProductoNuevo = leer(empleado, "/api/bootstrap");
        assertTrue(bootConProductoNuevo.contains("Producto cargado por empleado"));
        Long productoEmpleado = recs.findByCompanyIdAndKind(cid, "products").stream()
                .filter(r -> r.payload != null && r.payload.contains("\"title\":\"Producto cargado por empleado\""))
                .findFirst().orElseThrow().id;
        mvc.perform(put("/api/products/" + productoEmpleado).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edición no permitida\"}").session(empleado)).andExpect(status().is(403));
        llamar(empleado, "/api/purchases", "{\"requestId\":\"employee-purchase\",\"supplierId\":" + supplier + ",\"invoice\":\"F-2\",\"items\":[{\"productId\":" + product + ",\"qty\":1,\"unitPrice\":250.5}]}", 200);
        assertTrue(leer(empleado, "/api/bootstrap").contains("\"stock\":16"));
        mvc.perform(get("/api/fiscal/invoices").session(empleado)).andExpect(status().isOk());
        llamar(empleado, "/api/purchases/1/cancel", "{\"reason\":\"x\"}", 403);

        // tareas automáticas: sin token (o con token incorrecto) se rechazan
        mvc.perform(post("/api/internal/jobs/run")).andExpect(status().is(403));
        mvc.perform(post("/api/internal/jobs/run").header("X-Jobs-Token", "cualquiera")).andExpect(status().is(403));
    }

    @Test
    void paginaPublicaYPrecio() throws Exception {
        String plan = mvc.perform(get("/api/public/plan")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(plan.contains("30000"));
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
