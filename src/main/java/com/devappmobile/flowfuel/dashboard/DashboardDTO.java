package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.vehicle.EnergyType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Métricas agregadas do veículo. Para HYBRID, os campos `totalEnergy`, `averagePrice`, `averageConsumption` e respectivas unidades vêm nulos; use `breakdown` (combustível + elétrico separados).")
public class DashboardDTO {

    private Long vehicleId;

    @Schema(description = "Matriz energética do veículo. Define se a resposta vem em campos planos ou em `breakdown`.")
    private EnergyType energyType;

    private Long totalRefuels;

    @Schema(description = "Soma total gasta em todos os abastecimentos (sempre presente, inclusive em HYBRID).")
    private BigDecimal totalSpent;

    @Schema(description = "Soma de `energyAmount`. Para HYBRID é `null` (use `breakdown`).", nullable = true)
    private BigDecimal totalEnergy;

    @Schema(nullable = true)
    private BigDecimal averagePrice;

    @Schema(nullable = true)
    private Double averageConsumption;

    @Schema(description = "Unidade de `totalEnergy` (`litros` ou `kWh`). `null` para HYBRID.", nullable = true,
            example = "litros")
    private String energyUnit;

    @Schema(example = "R$/litro", nullable = true)
    private String priceUnit;

    @Schema(example = "km/L", nullable = true)
    private String consumptionUnit;

    @Schema(description = "Preenchido apenas para veículos HYBRID. Separa métricas de combustível e eletricidade.",
            nullable = true)
    private HybridBreakdownDTO breakdown;

    private LocalDate lastRefuelDate;

    private Integer lastOdometer;
}
