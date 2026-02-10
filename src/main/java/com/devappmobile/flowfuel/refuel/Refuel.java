package com.devappmobile.flowfuel.refuel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import com.devappmobile.flowfuel.vehicle.Vehicle;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refuels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Refuel {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime refuelDate;

    @Column(nullable = false)
    private Integer odometer;

    @Column(name = "km_since_last_refuel")
    private Integer kmSinceLastRefuel;

    @Column(name = "energy_amount", nullable = false)
    private BigDecimal energyAmount;

    @Column(name = "price_per_unit", nullable = false)
    private BigDecimal pricePerUnit;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "full_tank")
    private Boolean fullTank = false;

    @ManyToOne
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @PrePersist
    @PreUpdate
    public void calculateTotalAmount() {
        if (energyAmount != null && pricePerUnit != null) {
            this.totalAmount = energyAmount.multiply(pricePerUnit);
        }
    }

    public BigDecimal getLitersRefueled() {
        return energyAmount; // Para compatibilidade com código existente
    }

    public void setLitersRefueled(BigDecimal liters) {
        this.energyAmount = liters; // Para compatibilidade
    }

    public BigDecimal getPricePerLiter() {
        return pricePerUnit; // Para compatibilidade
    }

    public void setPricePerLiter(BigDecimal price) {
        this.pricePerUnit = price; // Para compatibilidade
    }
}