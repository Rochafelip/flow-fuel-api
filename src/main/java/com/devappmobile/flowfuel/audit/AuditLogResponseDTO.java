package com.devappmobile.flowfuel.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogResponseDTO {

    private Long id;
    private Long userId;
    private AuditAction action;
    private String ipAddress;
    private LocalDateTime createdAt;

    public static AuditLogResponseDTO from(AuditLog entity) {
        return AuditLogResponseDTO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .action(entity.getAction())
                .ipAddress(entity.getIpAddress())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
