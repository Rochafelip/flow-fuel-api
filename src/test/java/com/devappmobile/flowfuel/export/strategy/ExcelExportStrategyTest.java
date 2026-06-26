package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelExportStrategyTest {

    private final ExcelExportStrategy strategy = new ExcelExportStrategy();

    @Test
    void supportedFormat_retornaXLSX() {
        assertThat(strategy.supportedFormat()).isEqualTo(ExportFormat.XLSX);
    }

    @Test
    void export_comCabecalhoELinhas_geraPlanilhaComConteudoCorreto() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.of(
                new String[]{"25/06/2026", "MAINTENANCE", "150,00"},
                new String[]{"01/07/2026", "CAR_WASH", "40,00"}
        );

        byte[] result = strategy.export(headers, rows);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            assertThat(cellValue(headerRow, 0)).isEqualTo("Data");
            assertThat(cellValue(headerRow, 1)).isEqualTo("Tipo");
            assertThat(cellValue(headerRow, 2)).isEqualTo("Valor");
            XSSFCellStyle headerCellStyle = (XSSFCellStyle) headerRow.getCell(0).getCellStyle();
            assertThat(headerCellStyle.getFont().getBold()).isTrue();

            Row firstDataRow = sheet.getRow(1);
            assertThat(cellValue(firstDataRow, 0)).isEqualTo("25/06/2026");
            assertThat(cellValue(firstDataRow, 1)).isEqualTo("MAINTENANCE");

            Row secondDataRow = sheet.getRow(2);
            assertThat(cellValue(secondDataRow, 2)).isEqualTo("40,00");

            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitTopRow()).isEqualTo((short) 1);

            assertThat(sheet.getCTWorksheet().getAutoFilter()).isNotNull();
        }
    }

    @Test
    void export_semLinhas_geraApenasCabecalho() throws IOException {
        String[] headers = {"Data", "Tipo"};

        byte[] result = strategy.export(headers, List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            Row secondRow = sheet.getRow(1);
            assertThat(headerRow).isNotNull();
            assertThat(secondRow).isNull();
        }
    }

    private String cellValue(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell.getStringCellValue();
    }
}
