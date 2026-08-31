package com.devappmobile.flowfuel.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportFormatTest {

    @Test
    void fromString_csvEmQualquerCaixa_retornaCSV() {
        assertThat(ExportFormat.fromString("csv")).isEqualTo(ExportFormat.CSV);
        assertThat(ExportFormat.fromString("CSV")).isEqualTo(ExportFormat.CSV);
        assertThat(ExportFormat.fromString("Csv")).isEqualTo(ExportFormat.CSV);
    }

    @Test
    void fromString_xlsxEmQualquerCaixa_retornaXLSX() {
        assertThat(ExportFormat.fromString("xlsx")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.fromString("XLSX")).isEqualTo(ExportFormat.XLSX);
    }

    @Test
    void fromString_pdfEmQualquerCaixa_retornaPDF() {
        assertThat(ExportFormat.fromString("pdf")).isEqualTo(ExportFormat.PDF);
        assertThat(ExportFormat.fromString("PDF")).isEqualTo(ExportFormat.PDF);
    }

    @Test
    void fromString_valorInvalido_lancaIllegalArgumentException() {
        assertThatThrownBy(() -> ExportFormat.fromString("docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromString_nulo_lancaIllegalArgumentException() {
        assertThatThrownBy(() -> ExportFormat.fromString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
