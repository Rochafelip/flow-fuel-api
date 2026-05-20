package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.User;
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
        return buildDashboard(vehicleId);
    }

    private DashboardDTO buildDashboard(Long vehicleId) {
        Long totalRefuels = refuelRepository.countByVehicleId(vehicleId);

        BigDecimal totalSpent = refuelRepository
                .getTotalSpentByVehicleId(vehicleId)
                .orElse(BigDecimal.ZERO);

        BigDecimal totalEnergy = refuelRepository
                .getTotalEnergyByVehicleId(vehicleId)
                .orElse(BigDecimal.ZERO);

        BigDecimal averagePrice = refuelRepository
                .getAveragePricePerUnitByVehicleId(vehicleId)
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

        Double averageConsumption = calculateAverageConsumption(vehicleId);

        return DashboardDTO.builder()
                .vehicleId(vehicleId)
                .totalRefuels(totalRefuels)
                .totalSpent(totalSpent)
                .totalEnergy(totalEnergy)
                .averagePrice(averagePrice)
                .averageConsumption(averageConsumption)
                .lastRefuelDate(lastRefuelDate)
                .lastOdometer(lastOdometer)
                .build();
    }

    private Double calculateAverageConsumption(Long vehicleId) {
        List<Refuel> fullRefuels =
                refuelRepository.findFullTankRefuelsByVehicleId(vehicleId);

        if (fullRefuels.size() < 2) {
            return 0.0;
        }

        double totalKm = 0;
        double totalLiters = 0;

        for (int i = 0; i < fullRefuels.size() - 1; i++) {
            Refuel current = fullRefuels.get(i);
            Refuel previous = fullRefuels.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();
            double litersUsed = current.getEnergyAmount().doubleValue();

            if (kmDriven > 0 && litersUsed > 0) {
                totalKm += kmDriven;
                totalLiters += litersUsed;
            }
        }

        if (totalLiters == 0) {
            return 0.0;
        }

        double consumption = totalKm / totalLiters;

        return BigDecimal.valueOf(consumption)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
