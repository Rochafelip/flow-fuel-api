package com.devappmobile.flowfuel.vehicle.dto;

import com.devappmobile.flowfuel.vehicle.EnergyType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

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

    @Schema(description = "Capacidade do tanque de combustível em litros. Obrigatório para todos os tipos (para HYBRID/ELECTRIC sem combustível, pode ser ignorado pela validação se `batteryCapacity` cobrir a recarga).")
    @NotNull
    @Min(1)
    private Integer capacity;

    @Schema(description = "Capacidade da bateria em kWh. Recomendado para ELECTRIC e HYBRID; usado para validar `energyAmount` em recargas elétricas.",
            nullable = true, example = "60.0")
    private BigDecimal batteryCapacity;

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
