package com.devappmobile.flowfuel.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.devappmobile.flowfuel.vehicle.Vehicle;

import java.time.LocalDateTime;
import java.util.List;

@Entity(name = "User")
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    private String name;
    private String phone;

    @Column(name = "profile_picture")
    private String profilePicture;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<Vehicle> vehicles;

    public User(String email, String password, String name) {
        this.email = email;
        this.password = password;
        this.name = name;
    }

    // Métodos utilitários
    public void addVehicle(Vehicle vehicle) {
        vehicles.add(vehicle);
        vehicle.setUser(this);
    }

    public void removeVehicle(Vehicle vehicle) {
        vehicles.remove(vehicle);
        vehicle.setUser(null);
    }

}
