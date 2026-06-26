package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Component
public class ExcelExportStrategy implements ExportStrategy {

    private static final String SHEET_NAME = "Dados";

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.XLSX;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(SHEET_NAME);

            writeHeaderRow(workbook, sheet, headers);
            writeDataRows(sheet, rows);
            applyColumnAutoSize(sheet, headers.length);
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar planilha XLSX", e);
        }
    }

    private void writeHeaderRow(XSSFWorkbook workbook, XSSFSheet sheet, String[] headers) {
        XSSFFont boldFont = workbook.createFont();
        boldFont.setBold(true);
        XSSFCellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);

        XSSFRow headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            var cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDataRows(XSSFSheet sheet, List<String[]> rows) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XSSFRow row = sheet.createRow(rowIndex + 1);
            String[] fields = rows.get(rowIndex);
            for (int colIndex = 0; colIndex < fields.length; colIndex++) {
                row.createCell(colIndex).setCellValue(fields[colIndex]);
            }
        }
    }

    private void applyColumnAutoSize(XSSFSheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
