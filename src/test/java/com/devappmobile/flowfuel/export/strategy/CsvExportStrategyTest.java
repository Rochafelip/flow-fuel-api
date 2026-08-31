package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportStrategyTest {

    private final CsvExportStrategy strategy = new CsvExportStrategy();

    @Test
    void supportedFormat_retornaCSV() {
        assertThat(strategy.supportedFormat()).isEqualTo(ExportFormat.CSV);
    }

    @Test
    void export_prefixaConteudoComBomUtf8() {
        String[] headers = {"Data", "Preço"};
        List<String[]> rows = List.<String[]>of(new String[]{"25/06/2026", "150,00"});

        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);

        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertThat(java.util.Arrays.copyOfRange(result, 0, 3)).isEqualTo(bom);
    }

    @Test
    void export_comCabecalhoELinhas_geraCsvComVirgulaComoSeparador() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"25/06/2026", "MAINTENANCE", "150,00"});
        rows.add(new String[]{"01/07/2026", "CAR_WASH", "40,00"});

        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly(
                "Data,Tipo,Valor",
                "25/06/2026,MAINTENANCE,\"150,00\"",
                "01/07/2026,CAR_WASH,\"40,00\""
        );
    }

    @Test
    void export_comCampoContendoVirgula_envolveEmAspas() throws IOException {
        String[] headers = {"Data", "Descrição"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"25/06/2026", "Troca de óleo, filtro e correia"});

        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly(
                "Data,Descrição",
                "25/06/2026,\"Troca de óleo, filtro e correia\""
        );
    }

    @Test
    void export_semLinhas_geraApenasCabecalho() throws IOException {
        String[] headers = {"Data", "Tipo"};

        byte[] result = strategy.export(headers, new ArrayList<>(), ExportMetadata.EMPTY);
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly("Data,Tipo");
    }

    private List<String> readLines(byte[] content) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>(reader.lines().toList());
            if (!lines.isEmpty() && lines.get(0).startsWith("﻿")) {
                lines.set(0, lines.get(0).substring(1));
            }
            return lines;
        }
    }
}
