package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public ResponseEntity<?> createVehicle(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VehicleRequestDTO request) {
        return vehicleService.createVehicle(user, request);
    }

    @GetMapping
    public ResponseEntity<List<Vehicle>> getUserVehicles(@AuthenticationPrincipal User user) {
        return vehicleService.getUserVehicles(user);
    }

    @GetMapping("/active")
    public ResponseEntity<Vehicle> getActiveVehicle(@AuthenticationPrincipal User user) {
        return vehicleService.getActiveVehicle(user);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Vehicle> getVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleService.getVehicleById(user, id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Vehicle> updateVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody VehicleRequestDTO request) {
        return vehicleService.updateVehicle(user, id, request);
    }

    @PutMapping("/{id}/odometer")
    public ResponseEntity<Vehicle> updateOdometer(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestParam Integer currentKm) {
        return vehicleService.updateOdometer(user, id, currentKm);
    }

    @PutMapping("/{id}/active")
    public ResponseEntity<?> setActiveVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleService.setActiveVehicle(user, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleService.deleteVehicle(user, id);
    }
}
