package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VehicleService {
    
    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    
    public ResponseEntity<Vehicle> createVehicle(Long userId, Vehicle vehicle) {
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        if (vehicle.getType() == null || vehicle.getEnergyType() == null || 
            vehicle.getCurrentKm() == null || vehicle.getCapacity() == null) {
            return ResponseEntity.badRequest().build();
        }
        
        if (vehicle.getLicensePlate() != null && 
            vehicleRepository.existsByLicensePlateAndUserId(vehicle.getLicensePlate(), userId)) {
            return ResponseEntity.badRequest().build();
        }
        
        vehicle.setUser(user.get());
        Vehicle savedVehicle = vehicleRepository.save(vehicle);
        return ResponseEntity.ok(savedVehicle);
    }
    
    public ResponseEntity<List<Vehicle>> getUserVehicles(Long userId) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        
        List<Vehicle> vehicles = vehicleRepository.findByUserId(userId);
        return ResponseEntity.ok(vehicles);
    }
    
    public ResponseEntity<Vehicle> getVehicleById(Long id) {
        Optional<Vehicle> vehicle = vehicleRepository.findById(id);
        return vehicle.map(ResponseEntity::ok)
                     .orElse(ResponseEntity.notFound().build());
    }
    
    public ResponseEntity<Vehicle> updateVehicle(Long id, Vehicle vehicleDetails) {
        Optional<Vehicle> vehicleOptional = vehicleRepository.findById(id);
        if (vehicleOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Vehicle vehicle = vehicleOptional.get();
        
        if (vehicleDetails.getType() != null) {
            vehicle.setType(vehicleDetails.getType());
        }
        if (vehicleDetails.getEnergyType() != null) {
            vehicle.setEnergyType(vehicleDetails.getEnergyType());
        }
        if (vehicleDetails.getFuelSubType() != null) {
            vehicle.setFuelSubType(vehicleDetails.getFuelSubType());
        }
        if (vehicleDetails.getBrand() != null) {
            vehicle.setBrand(vehicleDetails.getBrand());
        }
        if (vehicleDetails.getModel() != null) {
            vehicle.setModel(vehicleDetails.getModel());
        }
        if (vehicleDetails.getColor() != null) {
            vehicle.setColor(vehicleDetails.getColor());
        }
        if (vehicleDetails.getCapacity() != null) {
            vehicle.setCapacity(vehicleDetails.getCapacity());
        }
        if (vehicleDetails.getManufactureYear() != null) {
            vehicle.setManufactureYear(vehicleDetails.getManufactureYear());
        }
        if (vehicleDetails.getModelYear() != null) {
            vehicle.setModelYear(vehicleDetails.getModelYear());
        }
        if (vehicleDetails.getPhoto() != null) {
            vehicle.setPhoto(vehicleDetails.getPhoto());
        }
        if (vehicleDetails.getLicensePlate() != null) {
            vehicle.setLicensePlate(vehicleDetails.getLicensePlate());
        }
        
        Vehicle updatedVehicle = vehicleRepository.save(vehicle);
        return ResponseEntity.ok(updatedVehicle);
    }
    
    public ResponseEntity<Vehicle> updateOdometer(Long id, Integer currentKm) {
        Optional<Vehicle> vehicleOptional = vehicleRepository.findById(id);
        if (vehicleOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Vehicle vehicle = vehicleOptional.get();
        if (currentKm < vehicle.getCurrentKm()) {
            return ResponseEntity.badRequest().build();
        }
        
        vehicle.setCurrentKm(currentKm);
        Vehicle updatedVehicle = vehicleRepository.save(vehicle);
        return ResponseEntity.ok(updatedVehicle);
    }
    
    public ResponseEntity<?> setActiveVehicle(Long userId, Long vehicleId) {
        Optional<User> user = userRepository.findById(userId);
        Optional<Vehicle> vehicle = vehicleRepository.findById(vehicleId);
        
        if (user.isEmpty() || vehicle.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        if (!vehicle.get().getUser().getId().equals(userId)) {
            return ResponseEntity.badRequest().build();
        }
        
        List<Vehicle> userVehicles = vehicleRepository.findByUserId(userId);
        for (Vehicle v : userVehicles) {
            v.setIsActive(false);
        }
        
        vehicle.get().setIsActive(true);
        vehicleRepository.saveAll(userVehicles);
        
        return ResponseEntity.ok().build();
    }
    
    public ResponseEntity<?> deleteVehicle(Long id) {
        if (!vehicleRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        
        vehicleRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
    
    public boolean validateEnergyAmount(Vehicle vehicle, Double amount) {
        if (vehicle.isElectric()) {
            return amount <= vehicle.getCapacity();
        } else {
            return amount <= vehicle.getCapacity();
        }
    }
    
    public String getConsumptionUnit(Vehicle vehicle) {
        return vehicle.isElectric() ? "km/kWh" : "km/L";
    }
    
    public Double calculateConsumption(Integer km, Double energy) {
        if (energy == 0) return 0.0;
        return (double) km / energy;
    }
    
    public String formatConsumption(Vehicle vehicle, Double consumption) {
        String unit = getConsumptionUnit(vehicle);
        return String.format("%.1f %s", consumption, unit);
    }
}