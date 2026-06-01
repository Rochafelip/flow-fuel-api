package com.devappmobile.flowfuel.refuel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
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

    @NotNull
    @Min(0)
    private Integer odometer;

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
