package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class PdfExportStrategy implements ExportStrategy {

    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
    private static final Font BODY_FONT = new Font(Font.HELVETICA, 9);
    private static final Color HEADER_BACKGROUND = new Color(51, 102, 51);

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.PDF;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata) {
        Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);

            for (String header : headers) {
                table.addCell(headerCell(header));
            }
            for (String[] row : rows) {
                for (String field : row) {
                    table.addCell(bodyCell(field));
                }
            }

            document.add(table);
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar PDF", e);
        } finally {
            document.close();
        }

        return out.toByteArray();
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setBackgroundColor(HEADER_BACKGROUND);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6);
        return cell;
    }

    private PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", BODY_FONT));
        cell.setPadding(5);
        return cell;
    }
}
