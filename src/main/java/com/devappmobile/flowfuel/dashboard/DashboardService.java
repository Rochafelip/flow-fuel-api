package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
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
    private final AuthorizationHelper authorizationHelper;

    public DashboardDTO getVehicleDashboard(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);
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

        BigDecimal costPerKm = calculateCostPerKm(
                refuelRepository.findByVehicleIdOrderByOdometerDesc(vehicleId));

        DashboardDTO.DashboardDTOBuilder builder = DashboardDTO.builder()
                .vehicleId(vehicleId)
                .energyType(vehicle.getEnergyType())
                .totalRefuels(totalRefuels)
                .totalSpent(totalSpent)
                .costPerKm(costPerKm)
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

    /**
     * Fórmula oficial de consumo médio do produto.
     *
     * <p>Para cada par consecutivo de abastecimentos tanque-cheio ordenados do mais recente
     * ao mais antigo (current, previous), calcula:
     * <pre>
     *   kmDriven   = current.odometer - previous.odometer
     *   energyUsed = current.energyAmount
     * </pre>
     * O consumo final é {@code SUM(kmDriven) / SUM(energyUsed)} sobre todos os pares válidos
     * (kmDriven > 0 e energyUsed > 0), arredondado para 2 casas decimais (HALF_UP).
     *
     * <p>Exemplo: 3 abastecimentos [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * → pares: (C-B): 800 km / 40 L; (B-A): 700 km / 35 L
     * → consumo = (800+700) / (40+35) = 1500/75 = 20,00 km/L
     *
     * <p>Retorna 0.0 se houver menos de 2 abastecimentos tanque-cheio ou energia total zero.
     *
     * <p>Nota: o campo {@code kmSinceLastRefuel} persistido na entidade Refuel é deliberadamente
     * ignorado para garantir consistência com o odômetro registrado pelo usuário.
     */
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

    /**
     * Custo médio por km rodado (R$/km), usando TODOS os abastecimentos (cheios ou
     * parciais) — diferente de {@code calculateAverageConsumption}, não exige tanque
     * cheio, pois todo valor pago entre dois abastecimentos custeou aquele trecho rodado.
     *
     * <p>Para cada par consecutivo (refuels ordenados por odômetro desc), soma
     * {@code kmDriven} e o {@code totalAmount} do abastecimento mais recente do par
     * (pares com kmDriven <= 0 são ignorados). Resultado: {@code SUM(totalAmount) / SUM(kmDriven)}.
     *
     * <p>Em veículos HYBRID, a lista recebida já combina fuel + electric (ordenada por
     * odômetro), então o resultado é naturalmente o custo combinado.
     */
    private BigDecimal calculateCostPerKm(List<Refuel> refuelsOrderedByOdometerDesc) {
        if (refuelsOrderedByOdometerDesc.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalKm = 0;
        BigDecimal totalSpentOnSegments = BigDecimal.ZERO;

        for (int i = 0; i < refuelsOrderedByOdometerDesc.size() - 1; i++) {
            Refuel current = refuelsOrderedByOdometerDesc.get(i);
            Refuel previous = refuelsOrderedByOdometerDesc.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();

            if (kmDriven > 0) {
                totalKm += kmDriven;
                totalSpentOnSegments = totalSpentOnSegments.add(current.getTotalAmount());
            }
        }

        if (totalKm == 0) {
            return BigDecimal.ZERO;
        }

        return totalSpentOnSegments
                .divide(BigDecimal.valueOf(totalKm), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
