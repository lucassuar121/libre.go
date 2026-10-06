# Libre.go (Java 21 + Spring Boot 3)

App de gestión para librerías y comercios: productos, ventas con ticket y factura por email, stock con historial de
movimientos, caja, clientes, proveedores, fichaje de empleados, panel del jefe y administración global.

## Ejecutar
Requisitos: Java 21 y Maven.

    mvn spring-boot:run

Abrí http://localhost:8080. Los datos quedan en `data/` (base H2 en archivo). Al arrancar se crea el administrador global.

## Configurar Gmail (envío de facturas y recuperación de contraseña)
Gmail no permite usar tu contraseña normal por SMTP: hace falta una **contraseña de aplicación**.

1. Entrá a https://myaccount.google.com/security y activá la **Verificación en 2 pasos** (si no la tenés).
2. Entrá a https://myaccount.google.com/apppasswords, poné un nombre (por ejemplo "Libre.go") y tocá **Crear**.
3. Google te muestra una clave de 16 letras. Copiala **sin espacios**: esa es `MAIL_PASSWORD`.
4. Definí las variables de entorno antes de arrancar (nunca van en el código):

Windows (PowerShell):

    $env:MAIL_USERNAME="tucuenta@gmail.com"
    $env:MAIL_PASSWORD="abcdefghijklmnop"
    mvn spring-boot:run

Linux / Mac:

    export MAIL_USERNAME="tucuenta@gmail.com"
    export MAIL_PASSWORD="abcdefghijklmnop"
    mvn spring-boot:run

Si no configurás Gmail, la app funciona igual: los emails no se envían y su contenido (incluido el enlace de
recuperación) se imprime en la consola del servidor. Si querés que el enlace del email apunte a otro dominio,
definí `BASE_URL` (por defecto `http://localhost:8080`).

## Administrador global
Usuario `lucas1`. Contraseña inicial `lucaspipo19`, definida una sola vez en `application.properties`
(`app.global-admin.password`) y guardada en la base con BCrypt. Podés cambiar la inicial con la variable
`GLOBAL_ADMIN_PASSWORD` antes del primer arranque. **Cambiala apenas ingreses** (botón "Mi contraseña").

## Probar todo
**1. Recuperar contraseña:** Crear cuenta (con tu Gmail) > Salir > "¿Olvidaste tu contraseña?" > tu Gmail >
abrí el enlace del correo (vale 30 minutos) > nueva contraseña dos veces > Ingresar con la nueva.
El mismo enlace ya no sirve una segunda vez.

**2. Venta completa:** Creá un empleado (Equipo y actividad > Nuevo usuario) y cargá productos (Stock > Ingresar
producto nuevo). Ingresá como empleado, fichá la entrada, Ventas > Nueva venta: buscá un producto, agregalo,
completá nombre, apellido y correo del cliente, elegí el medio de pago y confirmá. Verificá: el stock bajó, hay un
movimiento "Salida por venta" en Stock > Movimientos, el cliente aparece en Clientes (con la misma persona no se
duplica), la caja del turno suma la venta y llegó la factura en PDF al correo del cliente.

**3. Permisos:** creá dos empresas con dos cuentas distintas. Cada jefe solo ve lo suyo. Ingresá como `lucas1`:
ves todas las empresas, tocás "Abrir" y administrás la que elijas.

## Cliente en la venta, cierres de caja y proveedores
- **Nueva venta:** se elige el tipo de cliente: *Cliente registrado* (se busca en Clientes), *Nuevo cliente* (se registra y
  se asocia a la venta) o *Consumidor final* (sin datos personales y sin factura por email).
- **Caja:** solo el jefe (o el administrador global) puede corregir un cierre ya hecho, con el botón "Corregir".
  El permiso se valida en el servidor (`PUT /api/shift/{id}/diff`): un empleado recibe 403 aunque llame a la ruta directamente.
  Queda registrado quién corrigió y en la actividad del equipo.
- **Proveedores:** campo opcional "Página web" (los proveedores existentes no pierden datos).

## Seguridad
- Contraseñas con BCrypt (mínimo 8 caracteres). El token de recuperación es aleatorio (256 bits), vence a los 30
  minutos, se usa una sola vez y en la base solo se guarda su hash. La respuesta de "olvidé mi contraseña" es
  siempre la misma, exista o no el email.
- Cada endpoint valida el usuario de la sesión y su empresa en el backend. El encabezado `X-Company` solo lo
  respeta el administrador global; para cualquier otro usuario se ignora.
- No hay bloqueo por intentos fallidos de login ni HTTPS propio: para producción, ponelo detrás de HTTPS.

## Subirla a Google Cloud
Incluye `Dockerfile` para Cloud Run. Ahí los archivos no se conservan: usá PostgreSQL (Cloud SQL), agregando la
dependencia `org.postgresql:postgresql` al pom y las variables `DB_URL`, `spring.datasource.username` y
`spring.datasource.password`.
