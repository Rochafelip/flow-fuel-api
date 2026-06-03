package com.devappmobile.flowfuel.vehicleevent.dto;

import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class VehicleEventRequestDTO {

    @NotNull
    private Long vehicleId;

    @NotNull
    private VehicleEventType type;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @NotNull
    @PastOrPresent
    private LocalDate eventDate;

    @PositiveOrZero
    private Integer odometer;

    @Size(max = 2000)
    private String description;
}
