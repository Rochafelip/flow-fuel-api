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
import com.devappmobile.flowfuel.user.User;

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

    public void addRefuel(Refuel refuel) {
        refuels.add(refuel);
        refuel.setVehicle(this);
    }

    public void removeRefuel(Refuel refuel) {
        refuels.remove(refuel);
        refuel.setVehicle(null);
    }
}