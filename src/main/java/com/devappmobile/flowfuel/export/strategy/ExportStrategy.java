package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;

import java.util.List;

public interface ExportStrategy {

    ExportFormat supportedFormat();

    byte[] export(String[] headers, List<String[]> rows);
}
