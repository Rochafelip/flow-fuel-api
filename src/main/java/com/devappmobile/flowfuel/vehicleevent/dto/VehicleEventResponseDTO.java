package com.devappmobile.flowfuel.vehicleevent.dto;

import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleEventResponseDTO {

    private Long id;
    private Long vehicleId;
    private VehicleEventType type;
    private BigDecimal amount;
    private LocalDate eventDate;
    private Integer odometer;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VehicleEventResponseDTO from(VehicleEvent entity) {
        return VehicleEventResponseDTO.builder()
                .id(entity.getId())
                .vehicleId(entity.getVehicle() != null ? entity.getVehicle().getId() : null)
                .type(entity.getType())
                .amount(entity.getAmount())
                .eventDate(entity.getEventDate())
                .odometer(entity.getOdometer())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
