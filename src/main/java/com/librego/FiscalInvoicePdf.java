package com.librego;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.librego.Models.FiscalInvoice;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FiscalInvoicePdf {
    private FiscalInvoicePdf() {}
    static byte[] pdf(String company, FiscalInvoice i) throws Exception {
        Document d=new Document(PageSize.A5,30,30,30,30); ByteArrayOutputStream out=new ByteArrayOutputStream(); PdfWriter.getInstance(d,out); d.open();
        Font title=FontFactory.getFont(FontFactory.HELVETICA_BOLD,15), bold=FontFactory.getFont(FontFactory.HELVETICA_BOLD,9), normal=FontFactory.getFont(FontFactory.HELVETICA,9);
        Paragraph p=new Paragraph("COMPROBANTE FISCAL ELECTRÓNICO",title);p.setAlignment(Element.ALIGN_CENTER);d.add(p);
        Paragraph t=new Paragraph("Factura "+i.invoiceType+" · Punto de venta "+String.format("%05d",i.pointOfSale)+" · N° "+String.format("%08d",i.invoiceNumber),bold);t.setAlignment(Element.ALIGN_CENTER);t.setSpacingAfter(10);d.add(t);
        d.add(new Paragraph("Emisor: "+(i.issuerName==null||i.issuerName.isBlank()?company:i.issuerName),normal)); d.add(new Paragraph("CUIT emisor: "+i.issuerCuit,normal));
        d.add(new Paragraph("Receptor: "+i.buyerName,normal));d.add(new Paragraph("Documento receptor: "+i.buyerDocType+" "+i.buyerDocNumber,normal));d.add(new Paragraph("Fecha: "+i.issueDate,normal));
        PdfPTable items=new PdfPTable(new float[]{4,1,2,2});items.setWidthPercentage(100);items.setSpacingBefore(8);items.setSpacingAfter(8);
        for(String h:List.of("Detalle","Cant.","P. unit.","Subtotal")){PdfPCell cell=new PdfPCell(new Phrase(h,bold));cell.setPadding(4);items.addCell(cell);}
        List<Map<String,Object>> lines=new ObjectMapper().readValue(i.itemsJson==null?"[]":i.itemsJson,new TypeReference<List<Map<String,Object>>>(){});
        for(Map<String,Object> line:lines){double qty=num(line.get("qty")),price=num(line.get("price"));items.addCell(new Phrase(String.valueOf(line.getOrDefault("title","Producto")),normal));items.addCell(new Phrase(String.valueOf((long)qty),normal));items.addCell(new Phrase(money(price),normal));items.addCell(new Phrase(money(price*qty),normal));}
        d.add(items);
        d.add(new Paragraph("Neto gravado: "+money(i.netAmount),normal));d.add(new Paragraph("IVA: "+money(i.vatAmount),normal));
        Paragraph total=new Paragraph("TOTAL: "+money(i.total),title);total.setAlignment(Element.ALIGN_RIGHT);total.setSpacingBefore(8);d.add(total);
        d.add(new Paragraph("CAE: "+i.cae,bold));d.add(new Paragraph("Vencimiento del CAE: "+formatCae(i.caeExpiration),normal));
        d.add(new Paragraph("Ambiente: "+i.environment+". Conservar este comprobante.",normal));
        Image qr=Image.getInstance(qrBytes(qrUrl(i))); qr.scaleToFit(90,90); qr.setAlignment(Element.ALIGN_RIGHT); d.add(qr);
        d.close();return out.toByteArray();
    }
    private static double num(Object n){return n instanceof Number v?v.doubleValue():0;}
    private static byte[] qrBytes(String content) throws Exception {var matrix=new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 240, 240);ByteArrayOutputStream out=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",out);return out.toByteArray();}
    private static String qrUrl(FiscalInvoice i) {
        int docType=switch(i.buyerDocType==null?"":i.buyerDocType){case "CUIT"->80;case "DNI"->96;default->99;};
        String doc=(i.buyerDocNumber==null||i.buyerDocNumber.isBlank())?"0":i.buyerDocNumber.replaceAll("[^0-9]","");
        String json="{\"ver\":1,\"fecha\":\""+i.issueDate+"\",\"cuit\":"+i.issuerCuit.replaceAll("[^0-9]","")+",\"ptoVta\":"+i.pointOfSale+",\"tipoCmp\":"+typeCode(i.invoiceType)+",\"nroCmp\":"+i.invoiceNumber+",\"importe\":"+String.format(Locale.US,"%.2f",i.total)+",\"moneda\":\"PES\",\"ctz\":1,\"tipoDocRec\":"+docType+",\"nroDocRec\":"+doc+",\"tipoCodAut\":\"E\",\"codAut\":"+i.cae+"}";
        return "https://www.afip.gob.ar/fe/qr/?p="+Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
    private static int typeCode(String t){return switch(t){case "A"->1;case "B"->6;case "C"->11;default->0;};}
    private static String formatCae(String s){if(s==null||s.length()!=8)return s==null?"":s;return s.substring(6,8)+"/"+s.substring(4,6)+"/"+s.substring(0,4);}
    private static String money(Double n){return String.format(Locale.of("es","AR"),"$ %,.2f",n==null?0:n);}
}
