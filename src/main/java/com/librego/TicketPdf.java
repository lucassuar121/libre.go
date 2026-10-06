package com.librego;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Genera la factura/ticket de una venta como PDF, HTML y texto plano (se reutiliza para el email). */
final class TicketPdf {
    private TicketPdf() {}

    static final Map<String, String> PAY = Map.of("efectivo", "Efectivo", "tarjeta", "Tarjeta", "qr", "QR");

    static String money(double v) { return String.format(Locale.of("es", "AR"), "$ %,.0f", v); }
    static String when(Object at, String tz) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(Instant.parse(String.valueOf(at)).atZone(ZoneId.of(tz)));
    }
    static String number(Map<String, Object> s) { return String.format("%05d", ((Number) s.get("number")).longValue()); }
    static double d(Object o) { return ((Number) o).doubleValue(); }
    static String pay(Map<String, Object> s) { return PAY.getOrDefault(String.valueOf(s.get("payment")), String.valueOf(s.get("payment"))); }
    static String esc(Object o) { return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> s) { return (List<Map<String, Object>>) s.get("items"); }

    static PdfPCell cell(String t, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(t, f));
        c.setHorizontalAlignment(align);
        c.setBorder(Rectangle.BOTTOM);
        c.setPadding(4);
        return c;
    }

    static byte[] pdf(String company, Map<String, Object> s, String tz) throws Exception {
        Document doc = new Document(PageSize.A5, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font h = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font b = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        Font n = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Paragraph t = new Paragraph(company, h);
        t.setAlignment(Element.ALIGN_CENTER);
        doc.add(t);
        Paragraph t2 = new Paragraph("Factura / Ticket N° " + number(s), b);
        t2.setAlignment(Element.ALIGN_CENTER);
        t2.setSpacingAfter(10f);
        doc.add(t2);
        doc.add(new Paragraph("Fecha y hora: " + when(s.get("at"), tz), n));
        doc.add(new Paragraph("Cliente: " + s.get("clientName") + " (" + s.get("clientEmail") + ")", n));
        doc.add(new Paragraph("Atendió: " + s.get("user"), n));
        doc.add(new Paragraph("Medio de pago: " + pay(s), n));
        PdfPTable table = new PdfPTable(new float[]{4, 1, 2, 2});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12f);
        table.addCell(cell("Producto", b, Element.ALIGN_LEFT));
        table.addCell(cell("Cant.", b, Element.ALIGN_CENTER));
        table.addCell(cell("P. unit.", b, Element.ALIGN_RIGHT));
        table.addCell(cell("Subtotal", b, Element.ALIGN_RIGHT));
        for (Map<String, Object> i : items(s)) {
            double price = d(i.get("price")), qty = d(i.get("qty"));
            table.addCell(cell(String.valueOf(i.get("title")), n, Element.ALIGN_LEFT));
            table.addCell(cell(String.valueOf((long) qty), n, Element.ALIGN_CENTER));
            table.addCell(cell(money(price), n, Element.ALIGN_RIGHT));
            table.addCell(cell(money(price * qty), n, Element.ALIGN_RIGHT));
        }
        doc.add(table);
        Paragraph total = new Paragraph("TOTAL: " + money(d(s.get("total"))), h);
        total.setAlignment(Element.ALIGN_RIGHT);
        total.setSpacingBefore(10f);
        doc.add(total);
        Paragraph thanks = new Paragraph("¡Gracias por su compra!", n);
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(14f);
        doc.add(thanks);
        doc.close();
        return out.toByteArray();
    }

    static String text(String company, Map<String, Object> s, String tz) {
        StringBuilder sb = new StringBuilder();
        sb.append(company).append("\nFactura / Ticket N° ").append(number(s)).append("\n\n");
        sb.append("Cliente: ").append(s.get("clientName")).append("\nFecha y hora: ").append(when(s.get("at"), tz)).append("\n\n");
        for (Map<String, Object> i : items(s)) {
            double price = d(i.get("price")), qty = d(i.get("qty"));
            sb.append((long) qty).append(" x ").append(i.get("title")).append(" a ").append(money(price)).append(" = ").append(money(price * qty)).append("\n");
        }
        sb.append("\nTOTAL: ").append(money(d(s.get("total")))).append("\nMedio de pago: ").append(pay(s)).append("\n\nGracias por su compra.");
        return sb.toString();
    }

    static String html(String company, Map<String, Object> s, String tz) {
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> i : items(s)) {
            double price = d(i.get("price")), qty = d(i.get("qty"));
            rows.append("<tr><td style='padding:6px;border-bottom:1px solid #ddd'>").append(esc(i.get("title")))
                .append("</td><td style='padding:6px;border-bottom:1px solid #ddd;text-align:center'>").append((long) qty)
                .append("</td><td style='padding:6px;border-bottom:1px solid #ddd;text-align:right'>").append(money(price))
                .append("</td><td style='padding:6px;border-bottom:1px solid #ddd;text-align:right'>").append(money(price * qty)).append("</td></tr>");
        }
        return "<div style='font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#1b2a2a'>"
            + "<h2 style='margin-bottom:0'>" + esc(company) + "</h2>"
            + "<p style='margin-top:4px;color:#555'>Factura / Ticket N° <b>" + number(s) + "</b></p>"
            + "<p>Hola <b>" + esc(s.get("clientName")) + "</b>, gracias por tu compra. Te dejamos el detalle (también adjuntamos el PDF).</p>"
            + "<p style='color:#555'>Fecha y hora: " + when(s.get("at"), tz) + "<br>Atendió: " + esc(s.get("user")) + "</p>"
            + "<table style='width:100%;border-collapse:collapse'><tr style='background:#f2f4f3'><th style='padding:6px;text-align:left'>Producto</th>"
            + "<th style='padding:6px'>Cant.</th><th style='padding:6px;text-align:right'>Precio unit.</th><th style='padding:6px;text-align:right'>Subtotal</th></tr>"
            + rows + "</table>"
            + "<p style='text-align:right;font-size:18px'><b>Total: " + money(d(s.get("total"))) + "</b></p>"
            + "<p>Medio de pago: <b>" + esc(pay(s)) + "</b></p></div>";
    }
}
