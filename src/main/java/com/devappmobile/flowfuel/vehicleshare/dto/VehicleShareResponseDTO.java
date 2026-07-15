package com.devappmobile.flowfuel.vehicleshare.dto;

import com.devappmobile.flowfuel.vehicleshare.VehicleShare;
import com.devappmobile.flowfuel.vehicleshare.VehicleShareStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleShareResponseDTO {

    private Long id;
    private Long vehicleId;
    private String vehicleBrand;
    private String vehicleModel;
    private Long ownerId;
    private String ownerName;
    private Long guestId;
    private String guestName;
    private VehicleShareStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
    private LocalDateTime expiresAt;

    public static VehicleShareResponseDTO from(VehicleShare entity) {
        return VehicleShareResponseDTO.builder()
                .id(entity.getId())
                .vehicleId(entity.getVehicle().getId())
                .vehicleBrand(entity.getVehicle().getBrand())
                .vehicleModel(entity.getVehicle().getModel())
                .ownerId(entity.getOwner().getId())
                .ownerName(entity.getOwner().getName())
                .guestId(entity.getGuest().getId())
                .guestName(entity.getGuest().getName())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .respondedAt(entity.getRespondedAt())
                .expiresAt(entity.getExpiresAt())
                .build();
    }
}
