package com.devappmobile.flowfuel.vehicle;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "energy_type", nullable = false, length = 20)
    private EnergyType energyType;

    @Column(name = "fuel_sub_type")
    private String fuelSubType;

    @Column(name = "current_km", nullable = false)
    private Integer currentKm;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "battery_capacity", precision = 8, scale = 2)
    private BigDecimal batteryCapacity;

    private String brand;
    private String model;

    @Column(name = "manufacture_year")
    private Integer manufactureYear;

    @Column(name = "model_year")
    private Integer modelYear;

    private String color;
    
    private String licensePlate;  
    
    private String photo;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JsonIgnore
    @OneToMany(mappedBy = "vehicle", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Refuel> refuels = new ArrayList<>();

    public boolean isElectric() {
        return energyType != null && energyType.isElectric();
    }

    public String getEnergyUnit() {
        return energyType != null ? energyType.getEnergyUnit() : "litros";
    }

    public String getPriceUnit() {
        return energyType != null ? energyType.getPriceUnit() : "R$/litro";
    }

    public String getConsumptionUnit() {
        return energyType != null ? energyType.getConsumptionUnit() : "km/L";
    }

    public BigDecimal getEffectiveCapacity(RefuelType refuelType) {
        if (refuelType == RefuelType.ELECTRIC) {
            return batteryCapacity;
        }
        return capacity != null ? BigDecimal.valueOf(capacity) : null;
    }

    public RefuelType defaultRefuelType() {
        if (energyType == null) return RefuelType.FUEL;
        return switch (energyType) {
            case ELECTRIC -> RefuelType.ELECTRIC;
            case COMBUSTION -> RefuelType.FUEL;
            case HYBRID -> null;
        };
    }

    public boolean acceptsRefuelType(RefuelType refuelType) {
        if (refuelType == null || energyType == null) return false;
        return switch (energyType) {
            case COMBUSTION -> refuelType == RefuelType.FUEL;
            case ELECTRIC -> refuelType == RefuelType.ELECTRIC;
            case HYBRID -> true;
        };
    }

    public void addRefuel(Refuel refuel) {
        refuels.add(refuel);
        refuel.setVehicle(this);
    }

    public void removeRefuel(Refuel refuel) {
        refuels.remove(refuel);
        refuel.setVehicle(null);
    }
}