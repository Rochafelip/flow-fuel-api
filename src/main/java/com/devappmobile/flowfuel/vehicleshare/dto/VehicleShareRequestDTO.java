package com.devappmobile.flowfuel.vehicleshare.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VehicleShareRequestDTO {

    @NotNull
    private Long vehicleId;

    @NotBlank
    @Email
    private String inviteeEmail;

    @NotNull
    @Min(1)
    @Max(365)
    private Integer durationDays;
}
