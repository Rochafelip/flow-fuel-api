package com.devappmobile.flowfuel.vehicle.dto;

import com.devappmobile.flowfuel.vehicle.EnergyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VehicleRequestDTO {

    @NotBlank
    private String type;

    @NotNull
    private EnergyType energyType;

    private String fuelSubType;

    @NotNull
    @Min(0)
    private Integer currentKm;

    @NotNull
    @Min(1)
    private Integer capacity;

    private String brand;
    private String model;

    @Min(1886)
    @Max(2100)
    private Integer manufactureYear;

    @Min(1886)
    @Max(2100)
    private Integer modelYear;

    private String color;
    private String licensePlate;
}
