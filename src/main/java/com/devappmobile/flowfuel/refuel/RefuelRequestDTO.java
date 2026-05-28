package com.devappmobile.flowfuel.refuel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class RefuelRequestDTO {

    @NotNull
    private Long vehicleId;

    @Schema(description = "Quilômetros percorridos desde o último abastecimento. " +
            "O odômetro absoluto é calculado como (currentKm do veículo + trip).",
            example = "450", minimum = "1", maximum = "5000")
    @NotNull
    @Min(1)
    @Max(5000)
    private Integer trip;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal energyAmount;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal pricePerUnit;

    private Boolean fullTank = false;

    @Schema(description = "Tipo do abastecimento. Obrigatório para veículos HYBRID; inferido para COMBUSTION (FUEL) e ELECTRIC (ELECTRIC).",
            nullable = true, example = "FUEL")
    private RefuelType refuelType;
}
