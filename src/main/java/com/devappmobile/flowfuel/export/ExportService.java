package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.export.strategy.ExportStrategy;
import com.devappmobile.flowfuel.export.util.ExportFileNameBuilder;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String[] REFUEL_HEADERS =
            {"Data", "Combustível", "Litros/kWh", "Preço/unidade", "Total", "Odômetro"};

    private final RefuelRepository refuelRepository;
    private final VehicleEventRepository vehicleEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthorizationHelper authorizationHelper;
    private final Map<ExportFormat, ExportStrategy> strategiesByFormat;

    // Constructor is explicit (not @RequiredArgsConstructor) because strategiesByFormat
    // is derived from the injected strategies list, not a plain field assignment.
    public ExportService(RefuelRepository refuelRepository,
            VehicleEventRepository vehicleEventRepository,
            VehicleRepository vehicleRepository,
            AuthorizationHelper authorizationHelper,
            List<ExportStrategy> strategies) {
        this.refuelRepository = refuelRepository;
        this.vehicleEventRepository = vehicleEventRepository;
        this.vehicleRepository = vehicleRepository;
        this.authorizationHelper = authorizationHelper;
        this.strategiesByFormat = strategies.stream()
                .collect(Collectors.toMap(ExportStrategy::supportedFormat, Function.identity(), (a, b) -> {
                    throw new IllegalStateException(
                            "Múltiplas estratégias de exportação registradas para o mesmo formato: "
                                    + a.supportedFormat());
                }));
    }

    public ExportResult exportRefuels(User user, Long vehicleId, LocalDate startDate, LocalDate endDate,
            String formatParam) {
        ExportFormat format = parseFormat(formatParam);
        validateDateRange(startDate, endDate);

        Vehicle vehicle = findOwnedVehicle(user, vehicleId);

        List<Refuel> refuels;
        if (startDate != null && endDate != null) {
            refuels = refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                    vehicleId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        } else {
            refuels = refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId);
        }

        List<String[]> rows = refuels.stream().map(this::toRow).toList();
        byte[] content = strategiesByFormat.get(format).export(REFUEL_HEADERS, rows);
        String fileName = ExportFileNameBuilder.build("refuels", vehicle, format);

        return new ExportResult(content, fileName, contentTypeFor(format));
    }

    private String[] toRow(Refuel refuel) {
        return new String[]{
                refuel.getRefuelDate().format(DATE_FORMAT),
                refuel.getRefuelType().name(),
                refuel.getEnergyAmount().toPlainString(),
                refuel.getPricePerUnit().toPlainString(),
                refuel.getTotalAmount().toPlainString(),
                String.valueOf(refuel.getOdometer())
        };
    }

    private Vehicle findOwnedVehicle(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);
        return vehicle;
    }

    private ExportFormat parseFormat(String formatParam) {
        try {
            return ExportFormat.fromString(formatParam);
        } catch (IllegalArgumentException e) {
            throw new ExportValidationException(e.getMessage());
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new ExportValidationException("startDate e endDate devem ser informados juntos");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ExportValidationException("startDate não pode ser depois de endDate");
        }
    }

    private String contentTypeFor(ExportFormat format) {
        return format == ExportFormat.XLSX
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv";
    }
}
