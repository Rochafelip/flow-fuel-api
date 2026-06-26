package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CsvExportStrategy implements ExportStrategy {

    private static final String SEPARATOR = ",";
    private static final String LINE_BREAK = "\n";

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.CSV;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(toCsvLine(headers));

        for (String[] row : rows) {
            csv.append(LINE_BREAK).append(toCsvLine(row));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
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

        // Check if field needs escaping
        boolean needsEscaping = field.contains("\"") || field.contains("\n");

        // Only escape commas if the field contains non-numeric characters
        // This allows numeric values like "150,00" to pass through without quotes
        if (field.contains(",") && !isNumericField(field)) {
            needsEscaping = true;
        }

        if (needsEscaping) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private boolean isNumericField(String field) {
        // A numeric field contains only digits, commas, dots, and optional leading minus/plus
        return field.matches("^[-+]?[0-9]+([.,][0-9]+)*$");
    }
}
