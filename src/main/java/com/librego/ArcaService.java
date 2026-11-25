package com.librego;

import com.librego.Models.FiscalConfig;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Integración SOAP WSAA + WSFEv1. Los certificados y sus contraseñas se cifran en reposo. */
@Service
public class ArcaService {
    private static final String WSFE_NS = "http://ar.gov.afip.dif.FEV1/";
    private static final ZoneId AR = ZoneId.of("America/Argentina/Cordoba");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private final Map<Long, Ticket> tickets = new ConcurrentHashMap<>();
    @Value("${arca.encryption-key:}") private String encryptionKey;
    @Value("${arca.allow-production:false}") private boolean allowProduction;

    public record PublicConfig(String cuit, String legalName, String ivaCondition, Integer pointOfSale,
                               String environment, boolean certificateConfigured, Instant updatedAt) {}
    public record Result(long number, String cae, String caeExpiration, String invoiceType, int pointOfSale,
                         double total, double net, double vat, String environment, String rawSummary) {}
    private record Ticket(String token, String sign, Instant expiresAt, String environment) {}

    public PublicConfig publicView(FiscalConfig c) {
        return new PublicConfig(c.cuit, c.legalName, c.ivaCondition, c.pointOfSale, c.environment,
                c.encryptedPkcs12 != null && !c.encryptedPkcs12.isBlank(), c.updatedAt);
    }

    public void setCertificate(FiscalConfig c, String base64, String password) {
        if (base64 == null || base64.isBlank()) return;
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Ingresá la contraseña del certificado .p12/.pfx.");
        byte[] pfx;
        try { pfx = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("El certificado no está codificado correctamente."); }
        if (pfx.length < 100 || pfx.length > 2_000_000) throw new IllegalArgumentException("El certificado está vacío o supera el tamaño permitido.");
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12"); ks.load(new ByteArrayInputStream(pfx), password.toCharArray());
            if (!ks.aliases().hasMoreElements()) throw new IllegalArgumentException("El certificado PKCS#12 no contiene identidades.");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("No se pudo abrir el certificado. Revisá el archivo y su contraseña."); }
        c.encryptedPkcs12 = encrypt(pfx);
        c.encryptedCertificatePassword = encrypt(password.getBytes(StandardCharsets.UTF_8));
        tickets.remove(c.companyId);
    }

    public Result issue(FiscalConfig c, String invoiceType, String buyerName, String buyerDocType,
                        String buyerDocNumber, String buyerIvaCondition, double grossTotal, double ivaRate) throws Exception {
        if (c.encryptedPkcs12 == null || c.encryptedCertificatePassword == null)
            throw new IllegalStateException("Configurá el certificado fiscal de esta empresa antes de emitir.");
        if (c.pointOfSale == null || c.pointOfSale < 1 || c.pointOfSale > 99999) throw new IllegalStateException("Configurá un punto de venta habilitado para web service.");
        String type = (invoiceType == null ? "" : invoiceType.trim().toUpperCase(Locale.ROOT));
        int cbteType = switch (type) { case "A" -> 1; case "B" -> 6; case "C" -> 11; default -> throw new IllegalArgumentException("Elegí una factura A, B o C válida."); };
        if (grossTotal <= 0 || !Double.isFinite(grossTotal)) throw new IllegalArgumentException("El total de la venta no es válido.");
        if (type.equals("C") && !"MONOTRIBUTO".equals(c.ivaCondition) && !"EXENTO".equals(c.ivaCondition))
            throw new IllegalArgumentException("Factura C requiere una condición fiscal compatible con la configuración del emisor.");
        if (!type.equals("C") && !"RESPONSABLE_INSCRIPTO".equals(c.ivaCondition))
            throw new IllegalArgumentException("Factura A/B requiere que el emisor esté configurado como Responsable Inscripto.");
        String docType = buyerDocType == null ? "" : buyerDocType.trim().toUpperCase(Locale.ROOT);
        int docCode = switch (docType) { case "CUIT" -> 80; case "DNI" -> 96; case "CONSUMIDOR_FINAL" -> 99; default -> throw new IllegalArgumentException("Elegí CUIT, DNI o consumidor final para el receptor."); };
        String doc = buyerDocNumber == null ? "" : buyerDocNumber.replaceAll("[^0-9]", "");
        if (docCode == 99) doc = "0";
        if (docCode == 80 && (doc.length() != 11 || !validCuit(doc))) throw new IllegalArgumentException("El CUIT del receptor no es válido.");
        if (docCode == 96 && (doc.length() < 7 || doc.length() > 8)) throw new IllegalArgumentException("El DNI del receptor debe tener entre 7 y 8 dígitos.");
        if (type.equals("B") && "RESPONSABLE_INSCRIPTO".equals(buyerIvaCondition)) throw new IllegalArgumentException("Para un receptor Responsable Inscripto corresponde revisar la emisión de factura A.");
        if (buyerName == null || buyerName.isBlank() || buyerName.length() > 150) throw new IllegalArgumentException("Ingresá el nombre o razón social del receptor.");
        if (type.equals("A") && (!docType.equals("CUIT") || !"RESPONSABLE_INSCRIPTO".equals(buyerIvaCondition)))
            throw new IllegalArgumentException("Para factura A, el receptor debe tener CUIT y ser Responsable Inscripto.");
        if (!Set.of(2.5, 5.0, 10.5, 21.0, 27.0).contains(ivaRate)) throw new IllegalArgumentException("Alícuota de IVA no válida.");
        double net = type.equals("C") ? round(grossTotal) : round(grossTotal / (1.0 + ivaRate / 100.0));
        double vat = type.equals("C") ? 0.0 : round(grossTotal - net);
        String env = "PRODUCCION".equals(c.environment) ? "PRODUCCION" : "HOMOLOGACION";
        if (env.equals("PRODUCCION") && !allowProduction) throw new IllegalStateException("La emisión fiscal en producción está deshabilitada por seguridad. Primero probá en homologación y luego configurá ARCA_ALLOW_PRODUCTION=true.");
        String base = env.equals("PRODUCCION") ? "https://servicios1.afip.gov.ar/wsfev1/service.asmx" : "https://wswhomo.afip.gov.ar/wsfev1/service.asmx";
        Ticket t = getTicket(c, env);
        long last = lastAuthorized(base, t, c, cbteType);
        long next = last + 1;
        String issueDate = LocalDate.now(AR).format(DateTimeFormatter.BASIC_ISO_DATE);
        String ivaBlock = "";
        if (!type.equals("C") && vat > 0) {
            int ivaId = switch (Double.toString(ivaRate)) { case "2.5" -> 9; case "5.0" -> 8; case "10.5" -> 4; case "21.0" -> 5; case "27.0" -> 6; default -> 3; };
            ivaBlock = "<ar:Iva><ar:AlicIva><ar:Id>" + ivaId + "</ar:Id><ar:BaseImp>" + fmt(net) + "</ar:BaseImp><ar:Importe>" + fmt(vat) + "</ar:Importe></ar:AlicIva></ar:Iva>";
        }
        int receiverVat = receiverVatCode(buyerIvaCondition);
        String request = "<ar:FECAESolicitar><ar:Auth>" + authXml(t, c) + "</ar:Auth><ar:FeCAEReq><ar:FeCabReq><ar:CantReg>1</ar:CantReg><ar:PtoVta>"+c.pointOfSale+"</ar:PtoVta><ar:CbteTipo>"+cbteType+"</ar:CbteTipo></ar:FeCabReq><ar:FeDetReq><ar:FECAEDetRequest><ar:Concepto>1</ar:Concepto><ar:DocTipo>"+docCode+"</ar:DocTipo><ar:DocNro>"+doc+"</ar:DocNro><ar:CbteDesde>"+next+"</ar:CbteDesde><ar:CbteHasta>"+next+"</ar:CbteHasta><ar:CbteFch>"+issueDate+"</ar:CbteFch><ar:ImpTotal>"+fmt(grossTotal)+"</ar:ImpTotal><ar:ImpTotConc>0</ar:ImpTotConc><ar:ImpNeto>"+fmt(net)+"</ar:ImpNeto><ar:ImpOpEx>0</ar:ImpOpEx><ar:ImpIVA>"+fmt(vat)+"</ar:ImpIVA><ar:ImpTrib>0</ar:ImpTrib><ar:MonId>PES</ar:MonId><ar:MonCotiz>1</ar:MonCotiz><ar:CondicionIVAReceptorId>"+receiverVat+"</ar:CondicionIVAReceptorId>"+ivaBlock+"</ar:FECAEDetRequest></ar:FeDetReq></ar:FeCAEReq></ar:FECAESolicitar>";
        String response;
        try {
            response = soap(base, "FECAESolicitar", request);
        } catch (java.io.IOException lost) {
            // La conexión se cortó sin respuesta: ARCA pudo haber autorizado igual. Se consulta ese número antes de rendirse,
            // así no se pierde un CAE ya emitido ni se deja un hueco en la numeración.
            Result recovered = recover(base, t, c, cbteType, next, type, grossTotal, net, vat, env);
            if (recovered != null) return recovered;
            throw lost;
        }
        Document d = xml(response);
        String result = first(d, "Resultado");
        String cae = first(d, "CAE");
        String exp = first(d, "CAEFchVto");
        if (!"A".equals(result) || cae.isBlank()) {
            String errors = all(d, "Msg");
            if (errors.isBlank()) errors = all(d, "Code");
            throw new IllegalStateException("ARCA no autorizó la factura. " + (errors.isBlank() ? "Revisá la configuración fiscal y la respuesta del servicio." : errors));
        }
        String summary = "Resultado=" + result + "; CbteTipo=" + cbteType + "; PtoVta=" + c.pointOfSale;
        return new Result(next, cae, exp, type, c.pointOfSale, round(grossTotal), net, vat, env, summary);
    }

    /** Consulta si el comprobante {@code number} ya fue autorizado por ARCA con el mismo importe; devuelve null si no lo encuentra. */
    private Result recover(String base, Ticket t, FiscalConfig c, int cbteType, long number, String type,
                           double gross, double net, double vat, String env) {
        try {
            String body = "<ar:FECompConsultar><ar:Auth>"+authXml(t, c)+"</ar:Auth><ar:FeCompConsReq><ar:CbteTipo>"+cbteType+"</ar:CbteTipo><ar:CbteNro>"+number+"</ar:CbteNro><ar:PtoVta>"+c.pointOfSale+"</ar:PtoVta></ar:FeCompConsReq></ar:FECompConsultar>";
            Document d = xml(soap(base, "FECompConsultar", body));
            String cae = first(d, "CodAutorizacion"), exp = first(d, "FchVto"), total = first(d, "ImpTotal");
            if (cae.isBlank() || !"A".equals(first(d, "Resultado"))) return null;
            if (Math.abs(Double.parseDouble(total) - round(gross)) > 0.005) return null;
            return new Result(number, cae, exp, type, c.pointOfSale, round(gross), net, vat, env, "Recuperado por consulta; CbteTipo=" + cbteType + "; PtoVta=" + c.pointOfSale);
        } catch (Exception ignored) { return null; }
    }

    private long lastAuthorized(String base, Ticket t, FiscalConfig c, int cbteType) throws Exception {
        String body = "<ar:FECompUltimoAutorizado><ar:Auth>"+authXml(t, c)+"</ar:Auth><ar:PtoVta>"+c.pointOfSale+"</ar:PtoVta><ar:CbteTipo>"+cbteType+"</ar:CbteTipo></ar:FECompUltimoAutorizado>";
        Document d = xml(soap(base, "FECompUltimoAutorizado", body));
        String last = first(d, "CbteNro");
        if (last.isBlank()) throw new IllegalStateException("ARCA no devolvió la numeración actual del punto de venta.");
        return Long.parseLong(last);
    }

    public Result issueWithType(FiscalConfig c, String invoiceType, String buyerName, String buyerDocType,
                                String buyerDocNumber, String buyerIvaCondition, double grossTotal, double ivaRate) throws Exception {
        // Delegado a la implementación con consulta correlativa por tipo de comprobante.
        return issue(c, invoiceType, buyerName, buyerDocType, buyerDocNumber, buyerIvaCondition, grossTotal, ivaRate);
    }

    private Ticket getTicket(FiscalConfig c, String env) throws Exception {
        Ticket cached = tickets.get(c.companyId);
        if (cached != null && cached.environment.equals(env) && cached.expiresAt.isAfter(Instant.now().plusSeconds(60))) return cached;
        byte[] pfx = decrypt(c.encryptedPkcs12);
        String password = new String(decrypt(c.encryptedCertificatePassword), StandardCharsets.UTF_8);
        KeyStore ks = KeyStore.getInstance("PKCS12"); ks.load(new ByteArrayInputStream(pfx), password.toCharArray());
        String alias = null; Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) { String a = aliases.nextElement(); if (ks.isKeyEntry(a)) { alias = a; break; } }
        if (alias == null) throw new IllegalStateException("El certificado no contiene una clave privada.");
        Key key = ks.getKey(alias, password.toCharArray());
        if (!(key instanceof PrivateKey privateKey)) throw new IllegalStateException("No se encontró la clave privada del certificado.");
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        Instant now = Instant.now();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><loginTicketRequest version=\"1.0\"><header><uniqueId>"+now.getEpochSecond()+"</uniqueId><generationTime>"+java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(java.time.ZoneOffset.UTC).format(now.minusSeconds(600))+"</generationTime><expirationTime>"+java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(java.time.ZoneOffset.UTC).format(now.plusSeconds(600))+"</expirationTime></header><service>wsfe</service></loginTicketRequest>";
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(privateKey);
        var gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()).build(signer, cert));
        gen.addCertificates(new JcaCertStore(List.of(cert)));
        byte[] cms = gen.generate(new CMSProcessableByteArray(xml.getBytes(StandardCharsets.UTF_8)), true).getEncoded();
        String signed = Base64.getEncoder().encodeToString(cms);
        String endpoint = env.equals("PRODUCCION") ? "https://wsaa.afip.gov.ar/ws/services/LoginCms" : "https://wsaahomo.afip.gov.ar/ws/services/LoginCms";
        String envelope = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:w=\"http://wsaa.view.sua.dvadac.desein.afip.gov.ar/\"><soap:Header/><soap:Body><w:loginCms><w:in0>"+signed+"</w:in0></w:loginCms></soap:Body></soap:Envelope>";
        String response = postSoap(endpoint, "loginCms", envelope);
        Document d = xml(response);
        String ret = first(d, "loginCmsReturn");
        if (ret.isBlank()) ret = response;
        Document ticket = xml(ret);
        String token = first(ticket, "token"), sign = first(ticket, "sign");
        if (token.isBlank() || sign.isBlank()) throw new IllegalStateException("ARCA no devolvió un ticket de acceso válido.");
        // WSAA rechaza pedir un ticket nuevo mientras el anterior siga vigente (dura ~12 h): se usa el vencimiento real que informa ARCA.
        Instant exp = now.plusSeconds(540);
        String expText = first(ticket, "expirationTime");
        if (!expText.isBlank()) {
            try { exp = java.time.OffsetDateTime.parse(expText).toInstant(); } catch (Exception ignored) { /* conserva el valor conservador */ }
        }
        Ticket out = new Ticket(token, sign, exp, env); tickets.put(c.companyId, out); return out;
    }

    private String authXml(Ticket t, FiscalConfig c) { return "<ar:Token>"+xmlEscape(t.token)+"</ar:Token><ar:Sign>"+xmlEscape(t.sign)+"</ar:Sign><ar:Cuit>"+c.cuit.replaceAll("[^0-9]", "")+"</ar:Cuit>"; }

    private String soap(String endpoint, String action, String body) throws Exception {
        return postSoap(endpoint, WSFE_NS + action, "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ar=\""+WSFE_NS+"\"><soap:Header/><soap:Body>"+body+"</soap:Body></soap:Envelope>");
    }
    private String postSoap(String endpoint, String action, String envelope) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "text/xml; charset=utf-8").header("SOAPAction", "\""+action+"\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("ARCA respondió HTTP " + response.statusCode() + ". Revisá el ambiente y las credenciales.");
        return response.body();
    }
    private Document xml(String s) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }
    private String first(Document d, String name) { NodeList n=d.getElementsByTagNameNS("*", name); return n.getLength()==0?"":n.item(0).getTextContent().trim(); }
    private String all(Document d, String name) { NodeList n=d.getElementsByTagNameNS("*", name); StringJoiner j=new StringJoiner("; "); for(int i=0;i<n.getLength();i++){String v=n.item(i).getTextContent().trim();if(!v.isBlank())j.add(v);}return j.toString(); }
    private boolean validCuit(String cuit) {
        if (cuit == null || !cuit.matches("\\d{11}")) return false;
        int[] weights={5,4,3,2,7,6,5,4,3,2}; int sum=0;
        for(int i=0;i<10;i++)sum+=(cuit.charAt(i)-'0')*weights[i]; int r=11-(sum%11); int dv=r==11?0:r==10?9:r;
        return dv==cuit.charAt(10)-'0';
    }
    private int receiverVatCode(String v) { return switch (v==null?"":v.toUpperCase(Locale.ROOT)) { case "RESPONSABLE_INSCRIPTO" -> 1; case "MONOTRIBUTO" -> 6; case "EXENTO" -> 4; case "CONSUMIDOR_FINAL" -> 5; case "NO_CATEGORIZADO" -> 7; default -> throw new IllegalArgumentException("Elegí la condición frente al IVA del receptor."); }; }
    private static double round(double d) { return Math.round(d*100.0)/100.0; }
    private static String fmt(double d) { return String.format(Locale.US,"%.2f",d); }
    private static String xmlEscape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
    private String encrypt(byte[] input) {
        try { byte[] iv=new byte[12]; new java.security.SecureRandom().nextBytes(iv); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv)); byte[] out=c.doFinal(input); byte[] all=new byte[iv.length+out.length]; System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(out,0,all,iv.length,out.length);return Base64.getEncoder().encodeToString(all); }
        catch(Exception e){throw new IllegalStateException("No se pudo cifrar el certificado. Configurá ARCA_ENCRYPTION_KEY.");}
    }
    private byte[] decrypt(String input) {
        try { byte[] all=Base64.getDecoder().decode(input); byte[] iv=Arrays.copyOfRange(all,0,12); byte[] enc=Arrays.copyOfRange(all,12,all.length); Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));return c.doFinal(enc); }
        catch(Exception e){throw new IllegalStateException("No se pudo descifrar el certificado. Verificá que ARCA_ENCRYPTION_KEY sea la misma que se usó al guardarlo.");}
    }
    private SecretKeySpec key() {
        try { byte[] k=Base64.getDecoder().decode(encryptionKey); if(k.length!=32)throw new IllegalArgumentException();return new SecretKeySpec(k,"AES"); }
        catch(Exception e){throw new IllegalStateException("Configurá ARCA_ENCRYPTION_KEY como una clave aleatoria de 32 bytes codificada en Base64 antes de guardar certificados.");}
    }
}
