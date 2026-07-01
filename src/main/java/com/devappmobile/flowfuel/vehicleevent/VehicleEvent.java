package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "vehicle_events",
    indexes = {
        @Index(name = "idx_ve_vehicle_date", columnList = "vehicle_id,event_date"),
        @Index(name = "idx_ve_vehicle_type", columnList = "vehicle_id,type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VehicleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private VehicleEventType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", length = 2000)
    private String description;

    @PositiveOrZero
    @Column(name = "odometer")
    private Integer odometer;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
