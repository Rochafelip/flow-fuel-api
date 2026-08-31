package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportStrategyTest {

    private final PdfExportStrategy strategy = new PdfExportStrategy();

    @Test
    void supportedFormat_retornaPDF() {
        assertThat(strategy.supportedFormat()).isEqualTo(ExportFormat.PDF);
    }

    @Test
    void export_comCabecalhoELinhas_geraPdfComConteudoCorreto() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.of(
                new String[]{"25/06/2026", "MAINTENANCE", "150,00"},
                new String[]{"01/07/2026", "CAR_WASH", "40,00"}
        );

        byte[] result = strategy.export(headers, rows);

        try (PdfReader reader = new PdfReader(result)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Data", "Tipo", "Valor");
            assertThat(text).contains("25/06/2026", "MAINTENANCE", "150,00");
            assertThat(text).contains("01/07/2026", "CAR_WASH", "40,00");
        }
    }

    @Test
    void export_semLinhas_geraApenasCabecalho() throws IOException {
        String[] headers = {"Data", "Tipo"};

        byte[] result = strategy.export(headers, List.of());

        try (PdfReader reader = new PdfReader(result)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Data", "Tipo");
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }
}
