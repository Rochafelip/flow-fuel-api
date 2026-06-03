package com.devappmobile.flowfuel.vehicle.dto;

import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
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
public class VehicleResponseDTO {

    private Long id;
    private String type;
    private EnergyType energyType;
    private String fuelSubType;
    private Integer currentKm;
    private Integer capacity;
    private BigDecimal batteryCapacity;
    private String brand;
    private String model;
    private Integer manufactureYear;
    private Integer modelYear;
    private String color;
    private String licensePlate;
    private String photo;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VehicleResponseDTO from(Vehicle v) {
        return VehicleResponseDTO.builder()
                .id(v.getId())
                .type(v.getType())
                .energyType(v.getEnergyType())
                .fuelSubType(v.getFuelSubType())
                .currentKm(v.getCurrentKm())
                .capacity(v.getCapacity())
                .batteryCapacity(v.getBatteryCapacity())
                .brand(v.getBrand())
                .model(v.getModel())
                .manufactureYear(v.getManufactureYear())
                .modelYear(v.getModelYear())
                .color(v.getColor())
                .licensePlate(v.getLicensePlate())
                .photo(v.getPhoto())
                .isActive(v.getIsActive())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .build();
    }
}
