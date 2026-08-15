package com.devappmobile.flowfuel.dashboard;

import com.devappmobile.flowfuel.vehicle.EnergyType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    @Schema(description = "Soma de `totalSpent` (combustível) com todos os `vehicle_events` do veículo "
            + "(impostos, documentos, manutenção etc.). Gasto total real do veículo.")
    private BigDecimal totalOverallSpent;

    @Schema(description = "Composição de gastos por categoria (top 5 + 'OTHER' agregando o resto), "
            + "histórico completo, usada no gráfico de rosca. Vazio se não houver nenhum gasto.")
    private List<SpendingCategoryDTO> spendingBreakdown;

    @Schema(description = "Gasto total (combustível + eventos) dos últimos 6 meses corridos, "
            + "incluindo o mês atual, usado no gráfico de gastos mensais. Sempre 6 entradas, "
            + "ordenadas do mês mais antigo para o mais recente; meses sem gasto vêm com amount 0.")
    private List<MonthlySpendingDTO> monthlySpending;

    @Schema(description = "Custo médio por km rodado (R$/km), considerando todos os abastecimentos "
            + "(cheios ou parciais). Sempre presente, inclusive em HYBRID (combina combustível e elétrico).",
            example = "0.42")
    private BigDecimal costPerKm;

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
