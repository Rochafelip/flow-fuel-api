package com.devappmobile.flowfuel.export;

public enum ExportFormat {
    CSV,
    XLSX;

    public static ExportFormat fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Formato de exportação não informado");
        }
        try {
            return ExportFormat.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Formato de exportação inválido: " + value);
        }
    }
}
