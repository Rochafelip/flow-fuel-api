package com.devappmobile.flowfuel.vehicle;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class VehicleController {
    
    private final VehicleService vehicleService;
    
    @PostMapping
    public ResponseEntity<Vehicle> createVehicle(
            @RequestHeader Long userId, 
            @RequestBody Vehicle vehicle) {
        return vehicleService.createVehicle(userId, vehicle);
    }
    
    @GetMapping
    public ResponseEntity<List<Vehicle>> getUserVehicles(@RequestHeader Long userId) {
        return vehicleService.getUserVehicles(userId);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Vehicle> getVehicle(@PathVariable Long id) {
        return vehicleService.getVehicleById(id);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Vehicle> updateVehicle(
            @PathVariable Long id, 
            @RequestBody Vehicle vehicle) {
        return vehicleService.updateVehicle(id, vehicle);
    }
    
    @PutMapping("/{id}/odometer")
    public ResponseEntity<Vehicle> updateOdometer(
            @PathVariable Long id, 
            @RequestParam Integer currentKm) {
        return vehicleService.updateOdometer(id, currentKm);
    }
    
    @PutMapping("/{id}/active")
    public ResponseEntity<?> setActiveVehicle(
            @RequestHeader Long userId, 
            @PathVariable Long id) {
        return vehicleService.setActiveVehicle(userId, id);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVehicle(@PathVariable Long id) {
        return vehicleService.deleteVehicle(id);
    }
    
    @GetMapping("/active")
    public ResponseEntity<Vehicle> getActiveVehicle(@RequestHeader Long userId) {
        List<Vehicle> vehicles = vehicleService.getUserVehicles(userId).getBody();
        if (vehicles != null) {
            Vehicle activeVehicle = vehicles.stream()
                    .filter(Vehicle::getIsActive)
                    .findFirst()
                    .orElse(null);
            return activeVehicle != null ? ResponseEntity.ok(activeVehicle) : ResponseEntity.notFound().build();
        }
        return ResponseEntity.notFound().build();
    }
}