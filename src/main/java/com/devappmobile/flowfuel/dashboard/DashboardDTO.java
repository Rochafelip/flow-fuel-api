package com.devappmobile.flowfuel.dashboard;

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
public class DashboardDTO {

    private Long vehicleId;

    private Long totalRefuels;

    private BigDecimal totalSpent;

    private BigDecimal totalEnergy;

    private BigDecimal averagePrice;

    private Double averageConsumption;

    private LocalDate lastRefuelDate;

    private Integer lastOdometer;
}
