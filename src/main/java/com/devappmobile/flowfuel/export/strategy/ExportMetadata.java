package com.devappmobile.flowfuel.export.strategy;

import java.util.List;

public record ExportMetadata(String reportTitle, String vehicleLabel, String periodLabel, List<String> summaryLines) {

    public static final ExportMetadata EMPTY = new ExportMetadata("", "", "", List.of());
}
