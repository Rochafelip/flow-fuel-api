package com.devappmobile.flowfuel.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Breakdown de métricas para veículos híbridos: vetor combustível e vetor eletricidade separados.")
public class HybridBreakdownDTO {

    @Schema(description = "Métricas dos abastecimentos com `refuelType = FUEL` (litros).")
    private FuelMetrics fuel;

    @Schema(description = "Métricas dos abastecimentos com `refuelType = ELECTRIC` (kWh).")
    private FuelMetrics electric;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FuelMetrics {
        private BigDecimal totalEnergy;
        private BigDecimal totalSpent;
        private BigDecimal averagePrice;
        private Double averageConsumption;
        private String energyUnit;
        private String priceUnit;
        private String consumptionUnit;
    }
}
