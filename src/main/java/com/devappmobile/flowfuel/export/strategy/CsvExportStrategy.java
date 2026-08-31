package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CsvExportStrategy implements ExportStrategy {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String SEPARATOR = ",";
    private static final String LINE_BREAK = "\n";

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.CSV;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata) {
        StringBuilder csv = new StringBuilder();
        csv.append(toCsvLine(headers));

        for (String[] row : rows) {
            csv.append(LINE_BREAK).append(toCsvLine(row));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String toCsvLine(String[] fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(SEPARATOR);
            }
            line.append(escape(fields[i]));
        }
        return line.toString();
    }

    private String escape(String field) {
        if (field == null) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
