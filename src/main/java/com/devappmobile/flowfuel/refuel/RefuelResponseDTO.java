package com.devappmobile.flowfuel.refuel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefuelResponseDTO {

    private Long id;
    private Long vehicleId;
    private LocalDateTime refuelDate;
    private Integer odometer;
    private Integer kmSinceLastRefuel;
    private BigDecimal energyAmount;
    private BigDecimal pricePerUnit;
    private BigDecimal totalAmount;
    private Boolean fullTank;
    private RefuelType refuelType;

    public static RefuelResponseDTO from(Refuel r) {
        return RefuelResponseDTO.builder()
                .id(r.getId())
                .vehicleId(r.getVehicle() != null ? r.getVehicle().getId() : null)
                .refuelDate(r.getRefuelDate())
                .odometer(r.getOdometer())
                .kmSinceLastRefuel(r.getKmSinceLastRefuel())
                .energyAmount(r.getEnergyAmount())
                .pricePerUnit(r.getPricePerUnit())
                .totalAmount(r.getTotalAmount())
                .fullTank(r.getFullTank())
                .refuelType(r.getRefuelType())
                .build();
    }
}
