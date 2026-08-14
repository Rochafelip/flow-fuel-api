package com.devappmobile.flowfuel.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Uma fatia do gráfico de composição de gastos. `category` é o nome do "
        + "VehicleEventType ('FUEL', 'MAINTENANCE', ...) ou 'OTHER' para o agrupamento das "
        + "categorias fora do top 5.")
public record SpendingCategoryDTO(String category, BigDecimal amount) {}
