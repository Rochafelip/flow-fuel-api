package com.devappmobile.flowfuel.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Gasto total (combustível + eventos) de um mês específico, usado no "
        + "gráfico de gastos mensais. `month` no formato ISO 'yyyy-MM' (ex. '2026-08').")
public record MonthlySpendingDTO(String month, BigDecimal amount) {}
