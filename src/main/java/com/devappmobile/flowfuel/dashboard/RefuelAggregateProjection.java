package com.devappmobile.flowfuel.dashboard;

import java.math.BigDecimal;

public record RefuelAggregateProjection(
        Long count,
        BigDecimal totalSpent,
        BigDecimal totalEnergy,
        BigDecimal averagePrice
) {}
