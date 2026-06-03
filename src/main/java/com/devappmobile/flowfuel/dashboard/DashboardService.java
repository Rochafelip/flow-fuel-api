package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RefuelRepository refuelRepository;
    private final VehicleRepository vehicleRepository;

    public DashboardDTO getVehicleDashboard(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        if (!vehicle.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }
        return buildDashboard(vehicle);
    }

    private DashboardDTO buildDashboard(Vehicle vehicle) {
        Long vehicleId = vehicle.getId();
        Long totalRefuels = refuelRepository.countByVehicleId(vehicleId);

        BigDecimal totalSpent = refuelRepository
                .getTotalSpentByVehicleId(vehicleId)
                .orElse(BigDecimal.ZERO);

        Optional<Refuel> lastRefuelOpt =
                refuelRepository.findTopByVehicleIdOrderByRefuelDateDesc(vehicleId);

        LocalDate lastRefuelDate = null;
        Integer lastOdometer = null;
        if (lastRefuelOpt.isPresent()) {
            Refuel lastRefuel = lastRefuelOpt.get();
            lastRefuelDate = lastRefuel.getRefuelDate().toLocalDate();
            lastOdometer = lastRefuel.getOdometer();
        }

        DashboardDTO.DashboardDTOBuilder builder = DashboardDTO.builder()
                .vehicleId(vehicleId)
                .energyType(vehicle.getEnergyType())
                .totalRefuels(totalRefuels)
                .totalSpent(totalSpent)
                .lastRefuelDate(lastRefuelDate)
                .lastOdometer(lastOdometer);

        if (vehicle.getEnergyType() == EnergyType.HYBRID) {
            builder.breakdown(buildHybridBreakdown(vehicleId));
        } else {
            BigDecimal totalEnergy = refuelRepository
                    .getTotalEnergyByVehicleId(vehicleId)
                    .orElse(BigDecimal.ZERO);
            BigDecimal averagePrice = refuelRepository
                    .getAveragePricePerUnitByVehicleId(vehicleId)
                    .orElse(BigDecimal.ZERO);
            Double averageConsumption = calculateAverageConsumption(
                    refuelRepository.findFullTankRefuelsByVehicleId(vehicleId));

            builder.totalEnergy(totalEnergy)
                    .averagePrice(averagePrice)
                    .averageConsumption(averageConsumption)
                    .energyUnit(vehicle.getEnergyUnit())
                    .priceUnit(vehicle.getPriceUnit())
                    .consumptionUnit(vehicle.getConsumptionUnit());
        }

        return builder.build();
    }

    private HybridBreakdownDTO buildHybridBreakdown(Long vehicleId) {
        return HybridBreakdownDTO.builder()
                .fuel(buildFuelMetrics(vehicleId, RefuelType.FUEL, "litros", "R$/litro", "km/L"))
                .electric(buildFuelMetrics(vehicleId, RefuelType.ELECTRIC, "kWh", "R$/kWh", "km/kWh"))
                .build();
    }

    private HybridBreakdownDTO.FuelMetrics buildFuelMetrics(Long vehicleId, RefuelType type,
                                                            String energyUnit, String priceUnit,
                                                            String consumptionUnit) {
        BigDecimal totalEnergy = refuelRepository
                .getTotalEnergyByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        BigDecimal totalSpent = refuelRepository
                .getTotalSpentByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        BigDecimal averagePrice = refuelRepository
                .getAveragePricePerUnitByVehicleIdAndRefuelType(vehicleId, type)
                .orElse(BigDecimal.ZERO);
        Double averageConsumption = calculateAverageConsumption(
                refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(vehicleId, type));

        return HybridBreakdownDTO.FuelMetrics.builder()
                .totalEnergy(totalEnergy)
                .totalSpent(totalSpent)
                .averagePrice(averagePrice)
                .averageConsumption(averageConsumption)
                .energyUnit(energyUnit)
                .priceUnit(priceUnit)
                .consumptionUnit(consumptionUnit)
                .build();
    }

    private Double calculateAverageConsumption(List<Refuel> fullRefuels) {
        if (fullRefuels.size() < 2) {
            return 0.0;
        }

        double totalKm = 0;
        double totalEnergyUsed = 0;

        for (int i = 0; i < fullRefuels.size() - 1; i++) {
            Refuel current = fullRefuels.get(i);
            Refuel previous = fullRefuels.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();
            double energyUsed = current.getEnergyAmount().doubleValue();

            if (kmDriven > 0 && energyUsed > 0) {
                totalKm += kmDriven;
                totalEnergyUsed += energyUsed;
            }
        }

        if (totalEnergyUsed == 0) {
            return 0.0;
        }

        double consumption = totalKm / totalEnergyUsed;

        return BigDecimal.valueOf(consumption)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
