package com.devappmobile.flowfuel.export.util;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.devappmobile.flowfuel.vehicle.Vehicle;

import java.text.Normalizer;
import java.time.Year;
import java.util.regex.Pattern;

public final class ExportFileNameBuilder {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private ExportFileNameBuilder() {
    }

    public static String build(String resourceType, Vehicle vehicle, ExportFormat format) {
        String slug = vehicleSlug(vehicle);
        String extension = format == ExportFormat.XLSX ? "xlsx" : "csv";
        return "flowfuel-%s-%s-%d.%s".formatted(resourceType, slug, Year.now().getValue(), extension);
    }

    private static String vehicleSlug(Vehicle vehicle) {
        String brand = vehicle.getBrand() != null ? vehicle.getBrand() : "";
        String model = vehicle.getModel() != null ? vehicle.getModel() : "";
        String combined = (brand + " " + model).trim();

        if (combined.isEmpty()) {
            return "veiculo-" + vehicle.getId();
        }

        String normalized = Normalizer.normalize(combined, Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .toLowerCase();
        String slug = NON_ALPHANUMERIC.matcher(normalized).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }
}
