# Libre.go (Java 21 + Spring Boot 3)

App de gestión para librerías y comercios: productos, ventas con ticket y factura por email, stock con historial de
movimientos, caja, clientes, proveedores, fichaje de empleados, panel del jefe y administración global.

## Ejecutar
Requisitos: Java 21 y Maven.

    mvn spring-boot:run

Abrí http://localhost:8080. Los datos quedan en `data/` (base H2 en archivo, **solo para desarrollo**).
El administrador global solo se crea si definís `GLOBAL_ADMIN_PASSWORD` antes del primer arranque (ver abajo).

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
Usuario `lucas1`. **No hay contraseña por defecto**: se toma de la variable `GLOBAL_ADMIN_PASSWORD` la primera vez que
arranca con una base nueva y se guarda con BCrypt. En producción es obligatoria (mínimo 12 caracteres) y, si falta, la app no
arranca. Si tu base H2 local ya tenía a `lucas1`, conserva su contraseña actual. Cambiala desde el botón "Mi contraseña".

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

## Página principal, eliminar empresas, ingreso por cajas y cantidades
- **Página pública y contratación:** "Quiero contratar" pide el email de la cuenta de Mercado Pago y redirige al checkout seguro para autorizar la suscripción mensual. Libre.go no recibe ni almacena datos de tarjeta. El registro directo `/api/auth/register` está bloqueado. El servidor solo habilita el formulario de creación de empresa/usuario después de verificar por webhook el primer pago aprobado, con importe y moneda correctos. El estado se consulta con un token aleatorio que vuelve en la URL de retorno; el token almacenado en la base está hasheado. Las cuentas existentes conservan el login normal.
- **Eliminar empresa:** solo `lucas1` (validado en el servidor, `DELETE /api/admin/companies/{id}` exige confirmación).
  Borra únicamente esa empresa: sus usuarios y todos sus datos. Otras empresas y la cuenta `lucas1` no se tocan.
- **Stock por cajas:** al ingresar mercadería (movimiento de entrada o producto nuevo) se elige "Por unidades" o
  "Por cajas / paquetes". El total se calcula en el servidor y queda en Movimientos (ej.: "300 cajas × 100 unidades").
- **Nueva venta:** la cantidad se escribe con el teclado (en el buscador y en el detalle de la venta). Enter en el
  buscador pasa a la cantidad y Enter en la cantidad agrega el producto. No admite negativos, texto ni decimales,
  ni supera el stock.

## Estadísticas, turnos configurables y ventas por turno
- **Estadísticas (solo jefe):** se consultan por Día, Mes o Año, eligiendo cualquier mes o año con ventas. Muestran ganancia,
  ingresos, cantidad de ventas, unidades vendidas, producto más vendido, ranking Top 10/20/50/100, ingresos por medio de pago,
  ingresos por mes y la evolución de ingresos del período (por hora, por día o acumulada del año). Usan las ventas reales.
- **Configuración de turnos (solo jefe):** el jefe crea, edita, desactiva y elimina los turnos (nombre y horario). Nada está fijo en el código.
  Si no hay turnos configurados, los empleados fichan como antes, sin turno.
- **Empleado:** al fichar la entrada elige su mostrador y uno de los turnos activos (puede cambiarlos desde "Cambiar mostrador").
  Cada venta guarda empleado, turno, mostrador, fecha y hora.
- **Qué ve el empleado (validado en el servidor):** solo las ventas de HOY de su turno actual (de cualquier mostrador de ese turno)
  y solo su turno abierto en Caja. No recibe ventas de otros días ni de otros turnos, ni el historial de turnos cerrados.
- **Ventas (jefe):** filtros por fecha, turno y mostrador. Las ventas anteriores a esta versión no tienen turno: se muestran con "—"
  y solo las ve el jefe.
- La fecha "de hoy" para el empleado se calcula con la zona horaria `app.timezone` (por defecto America/Argentina/Cordoba).

## Suscripción mensual con renovación automática (Mercado Pago)
**Cómo funciona**
- Cada empresa tiene un estado (`PENDIENTE_PAGO`, `ACTIVA`, `VENCIDA`) y un vencimiento. Una empresa nueva empieza `PENDIENTE_PAGO`: el jefe solo ve la
  pantalla de suscripción. Con la empresa pendiente o vencida, **el servidor rechaza con error 402 cualquier operación normal** (jefe o empleado, aunque
  llamen a la API directamente). El corte por vencimiento se calcula en cada pedido con la fecha real: no depende de que alguien abra la aplicación.
- **Alta:** el jefe toca "Suscribirme", indica el email de su cuenta de Mercado Pago y autoriza el cobro mensual recurrente (API de *suscripciones*,
  `preapproval`). La empresa **no se activa** por volver de Mercado Pago: se activa cuando el servidor recibe el aviso y **vuelve a consultar el cobro a
  Mercado Pago** y comprueba que está `approved`, en ARS y por el importe acordado.
- **Renovación:** Mercado Pago cobra cada mes y avisa por webhook. Cada cobro aprobado suma un mes al vencimiento (fechas reales de calendario: 1/2 -> 1/3).
  Los cobros rechazados o pendientes no extienden nada. El id del pago es único y se bloquea la empresa mientras se procesa: un aviso repetido no renueva dos veces.
- **Baja:** botón "Dar de baja la suscripción" (con confirmación). El servidor cancela la renovación en Mercado Pago con su mecanismo oficial
  (`PUT /preapproval/{id}` con `status=cancelled`) y **solo si Mercado Pago confirma** registra la baja. Si falla, se informa y la baja no figura como hecha.
  La empresa conserva el acceso hasta el fin del último período pagado.
- **Eliminación automática:** cuando venció el último período pagado de una empresa que pidió la baja (y Mercado Pago confirma que la suscripción está cancelada),
  una tarea automática elimina la empresa y sus datos (usuarios, productos, ventas, stock, compras, clientes...). **Se conservan el historial de pagos y la auditoría.**
  No se elimina por pedir la baja, ni por vencer sin haber pedido la baja (en ese caso solo pierde el acceso). Cada empresa se procesa en su propia transacción
  (si una falla, las demás siguen), con la fila bloqueada y re-verificando condiciones: es seguro repetirla y con varias instancias.
- El precio ($30.000) se define **una sola vez**: `app.subscription.price` (variable `SUBSCRIPTION_PRICE`). Cambiarlo no modifica el importe de las suscripciones ya creadas.

**Tareas automáticas.** Hay dos mecanismos (usá al menos uno):
1. Interno: el servidor ejecuta la tarea cada hora (`@Scheduled`). En Cloud Run puede no ejecutarse si no hay tráfico.
2. **Recomendado en Cloud Run: Cloud Scheduler.** Definí `JOBS_TOKEN` (un secreto largo) y creá un job de Cloud Scheduler cada hora con destino HTTP
   `POST https://TU-DOMINIO/api/internal/jobs/run` y el encabezado `X-Jobs-Token: <tu JOBS_TOKEN>`. Sin `JOBS_TOKEN` ese endpoint queda deshabilitado.
`SUBSCRIPTION_DELETE_GRACE_DAYS` (por defecto 0) agrega días de gracia entre el vencimiento y la eliminación de una empresa dada de baja. Como la eliminación no se puede
deshacer, conviene poner algunos días (por ejemplo 7). `JOBS_ENABLED=false` apaga la tarea interna.

**Datos nuevos en la base** (Hibernate los agrega al arrancar con `ddl-auto=update`; son columnas/tablas nuevas y anulables, no se pierde ni modifica nada existente):
- `company`: `sub_status`, `sub_start`, `sub_expires`, `mp_preapproval_id`, `mp_sub_ref`, `mp_preapproval_status`, `mp_sub_amount`, `sub_cancel_requested_at`, `sub_cancel_confirmed_at`.
- `payment` (pagos, incluye `company_name`, `preapproval_id`), `signup_intent` (contrataciones públicas pendientes; no crea empresas ni usuarios antes del primer pago aprobado) y `audit_event` (auditoría: altas, cobros aprobados, discrepancias, bajas, fallos, accesos revocados, empresas eliminadas).
  Los nombres físicos los define Hibernate (`models$...` en tu base actual). No agregué Flyway: con los nombres actuales no podía garantizar scripts correctos para tu esquema.
- Empresas que ya existían: al arrancar quedan `PENDIENTE_PAGO` (o `ACTIVA` por N días con `SUBSCRIPTION_GRANT_EXISTING_DAYS=N`, una sola vez).

**Qué configurar en Mercado Pago** (los nombres del panel pueden variar)
1. Aplicación en https://www.mercadopago.com.ar/developers/panel con credenciales de **prueba** primero y de **producción** después.
2. **Webhooks:** cargá `https://TU-DOMINIO/api/mercadopago/webhook` y activá los eventos **Pagos** y **Planes y suscripciones** (`subscription_preapproval`,
   `subscription_authorized_payment`). Copiá la clave secreta a `MERCADOPAGO_WEBHOOK_SECRET`.
3. Variables: `MERCADOPAGO_ACCESS_TOKEN` del vendedor que recibirá el dinero, `MERCADOPAGO_WEBHOOK_URL` (la misma URL), `MERCADOPAGO_WEBHOOK_SECRET`, `BASE_URL` (URL pública HTTPS exacta) y `SUBSCRIPTION_PRICE`.
4. El email que escribe el interesado debe ser el de su cuenta de Mercado Pago. En pruebas hay que usar credenciales y cuentas de prueba de vendedor y comprador. El dinero solo se acreditará a la cuenta asociada al Access Token configurado.

**Cómo probar sin pagos reales**
- Sin Mercado Pago: `lucas1` > Administración Global > "Suscripción" de una empresa (activar, marcar vencida o pendiente) y la tarea manual:
  `curl -X POST -H "X-Jobs-Token: ..." https://.../api/internal/jobs/run`.
- Con Mercado Pago de prueba: token de prueba + `MERCADOPAGO_SANDBOX=true` + cuentas de prueba, y una URL pública (ngrok, cloudflared) para el webhook.

## Compras, filtros de fecha y buscadores
- **Compras (solo jefe, porque incluyen costos):** proveedor existente, productos del inventario, cantidades enteras, precios, subtotales y total calculados en el servidor,
  fecha, comprobante y observaciones. Al confirmar suma el stock y registra los movimientos **en una sola transacción** (todo se valida antes de modificar nada).
  Es idempotente: cada formulario manda un identificador único y un reintento o doble clic no vuelve a sumar. No permite repetir el comprobante de un proveedor.
  **Anular** revierte el stock con movimientos de salida y deja la compra como ANULADA (quién, cuándo y por qué); si el stock ya no alcanza o un producto fue eliminado,
  rechaza la anulación sin tocar nada. Historial con filtros por fecha, proveedor y comprobante.
- **Movimientos de stock:** filtro por fecha exacta (calendario) con la zona horaria de la aplicación (`app.timezone`, por defecto America/Argentina/Cordoba). **El empleado
  no recibe los movimientos** (el servidor no se los envía) y no hay otra ruta que los entregue; ve el stock actual y puede operarlo como antes.
- **Caja:** filtro por fecha (turnos que empezaron ese día) con los mismos totales, faltantes y sobrantes. Solo el jefe ve el historial de cajas.
- **Proveedores:** buscador por nombre (parcial, sin distinguir mayúsculas ni acentos).
- Todas las fechas se muestran en la zona horaria de la aplicación.

## Seguridad
- Contraseñas con BCrypt (mínimo 8 caracteres). El token de recuperación es aleatorio (256 bits), vence a los 30
  minutos, se usa una sola vez y en la base solo se guarda su hash. La respuesta de "olvidé mi contraseña" es
  siempre la misma, exista o no el email.
- Cada endpoint valida el usuario de la sesión y su empresa en el backend. El encabezado `X-Company` solo lo
  respeta el administrador global; para cualquier otro usuario se ignora.
- Límites de intentos: 10 logins fallidos por usuario y IP cada 15 minutos, 10 altas de cuenta y 5 solicitudes de contacto por IP y hora.
- Cada login renueva el id de sesión. Cabeceras de seguridad y `Cache-Control: no-store` en la API.
- Los errores inesperados no devuelven detalles internos; los logs no incluyen contraseñas, tokens, secretos ni el contenido de los emails.
- La app no maneja HTTPS por sí sola: en producción va detrás de HTTPS (Cloud Run lo da).

## Producción (Google Cloud Run + PostgreSQL)
**Perfiles.** Sin perfil = desarrollo local con H2. Con `SPRING_PROFILES_ACTIVE=prod` (el `Dockerfile` ya lo define) usa PostgreSQL,
cookie de sesión segura y exige la configuración crítica: si falta algo, **la app no arranca** y el log dice qué falta.
La base H2 sigue disponible para desarrollo (no se eliminó).

**Variables de entorno de producción** (modelo en `.env.example`; guardá los secretos en Secret Manager y pasalos con `--set-secrets`):

| Variable | Obligatoria | Para qué |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | Sí (ya está en el Dockerfile) | Activa el perfil de producción |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Sí | PostgreSQL, por ejemplo `jdbc:postgresql://HOST:5432/librego` |
| `BASE_URL` | Sí | URL pública con `https://` (emails y retornos de Mercado Pago) |
| `GLOBAL_ADMIN_PASSWORD` | Sí (si `lucas1` no existe aún) | Mínimo 12 caracteres |
| `MERCADOPAGO_ACCESS_TOKEN` | Para cobrar | Si está, `MERCADOPAGO_WEBHOOK_URL` pasa a ser obligatoria |
| `MERCADOPAGO_WEBHOOK_URL` | Con el token | `https://TU-DOMINIO/api/mercadopago/webhook` |
| `MERCADOPAGO_WEBHOOK_SECRET` | Sí en producción | Valida la firma de los avisos del webhook |
| `JOBS_TOKEN` | Recomendada en Cloud Run | Cloud Scheduler ejecuta las tareas automáticas (bajas y vencimientos) |
| `SUBSCRIPTION_DELETE_GRACE_DAYS` | No | Días de gracia antes de eliminar empresas dadas de baja |
| `MERCADOPAGO_PUBLIC_KEY`, `MERCADOPAGO_SANDBOX` | No | Reservada / modo prueba |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Para enviar emails | Gmail con contraseña de aplicación |
| `PORT` | No | Cloud Run lo define solo (la app ya lo usa) |
| `SUBSCRIPTION_PRICE`, `CONTACT_EMAIL`, `DB_POOL_SIZE`, `DDL_AUTO` | No | Precio (30000), aviso de contratos, conexiones, esquema |
| `CORS_ALLOWED_ORIGINS`, `SESSION_COOKIE_SAMESITE` | Solo si el frontend está en otro dominio | Hoy el frontend se sirve desde la misma app: no hace falta |

**Qué se pierde en Cloud Run:** el disco es temporal. Todo lo que hoy está en `./data` (la base H2: empresas, usuarios, ventas, stock,
pagos) desaparece al reiniciar: por eso en producción se usa PostgreSQL. Los datos de H2 **no se migran solos** a PostgreSQL. La app
no guarda ningún otro archivo (los PDF y los emails se generan en memoria). Las sesiones están en memoria de la instancia: si Cloud Run
reinicia o crea otra instancia, los usuarios tienen que volver a iniciar sesión.

**Despliegue de ejemplo** (revisá la documentación de Google por si cambian opciones):

    gcloud run deploy libre-go --source . --region REGION --allow-unauthenticated \
      --max-instances=1 --cpu-boost \
      --set-env-vars SPRING_PROFILES_ACTIVE=prod,BASE_URL=https://TU-DOMINIO,DB_URL=jdbc:postgresql://HOST:5432/librego,DB_USER=librego \
      --set-secrets DB_PASSWORD=db-password:latest,GLOBAL_ADMIN_PASSWORD=admin-password:latest

`--max-instances=1` evita que dos instancias tengan sesiones distintas. Para escalar a varias hay que mover las sesiones a la base
(Spring Session JDBC): se puede agregar más adelante. Para conectar Cloud SQL sin dependencias extra usá su IP privada con
"Direct VPC egress" (el driver de PostgreSQL ya está incluido). Después de desplegar, cargá la URL del webhook en Mercado Pago.

## Probar localmente antes de subirlo
1. **Compilar y correr los tests** (arrancan Spring con H2 en memoria, ejecutan todas las consultas y el flujo de suscripción):

       mvn clean verify

   Si esto pasa, no hay errores de entidades, repositorios ni inyección. Si algo falla, el informe queda en `target/surefire-reports`.
2. **Arrancar en modo desarrollo** (H2). PowerShell: `$env:GLOBAL_ADMIN_PASSWORD="una-clave-larga"; mvn spring-boot:run`.
   Bash: `GLOBAL_ADMIN_PASSWORD=una-clave-larga mvn spring-boot:run`. Comprobá `http://localhost:8080` y `http://localhost:8080/api/public/plan`.
3. **Probar contra PostgreSQL** (con Docker): `docker run --name pg-libre -e POSTGRES_PASSWORD=devpass -e POSTGRES_DB=librego -p 5432:5432 -d postgres:16`
   y arrancá con `DB_URL=jdbc:postgresql://localhost:5432/librego`, `SPRING_DATASOURCE_USERNAME=postgres`, `SPRING_DATASOURCE_PASSWORD=devpass`.
4. **Probar el perfil de producción en tu PC:** además `SPRING_PROFILES_ACTIVE=prod`, `DB_USER`, `DB_PASSWORD`, `BASE_URL=https://ejemplo.com` y una
   `GLOBAL_ADMIN_PASSWORD` de 12+ caracteres. Sin alguna de ellas debe negarse a arrancar con un mensaje claro.
5. **Imagen de Docker:** `docker build -t libre-go .` y `docker run -p 8080:8080 -e ... libre-go` (con las variables de producción).
