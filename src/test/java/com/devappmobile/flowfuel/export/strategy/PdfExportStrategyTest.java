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

        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);

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

        byte[] result = strategy.export(headers, List.of(), ExportMetadata.EMPTY);

        try (PdfReader reader = new PdfReader(result)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Data", "Tipo");
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void export_comMetadataPreenchida_incluiTituloVeiculoPeriodoEResumoAntesDaTabela() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.<String[]>of(new String[]{"25/06/2026", "MAINTENANCE", "150,00"});
        ExportMetadata metadata = new ExportMetadata(
                "Relatório de Eventos",
                "Toyota Corolla — ABC1D23",
                "01/01/2026 – 31/01/2026",
                List.of("Total gasto: R$ 150,00", "Eventos: 1", "MAINTENANCE: 1"));

        byte[] result = strategy.export(headers, rows, metadata);

        try (PdfReader reader = new PdfReader(result)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Relatório de Eventos");
            assertThat(text).contains("Toyota Corolla — ABC1D23");
            assertThat(text).contains("01/01/2026 – 31/01/2026");
            assertThat(text).contains("Total gasto: R$ 150,00");
            assertThat(text).contains("Eventos: 1");
            assertThat(text).contains("MAINTENANCE: 1");
            assertThat(text).contains("Data", "Tipo", "Valor", "150,00");
        }
    }
}
