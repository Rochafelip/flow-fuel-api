package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import com.devappmobile.flowfuel.vehicle.dto.VehicleResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping
    public VehicleResponseDTO createVehicle(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VehicleRequestDTO request) {
        return vehicleService.createVehicle(user, request);
    }

    @GetMapping
    public PageResponseDTO<VehicleResponseDTO> getUserVehicles(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return vehicleService.getUserVehicles(user, pageable);
    }

    @GetMapping("/active")
    public VehicleResponseDTO getActiveVehicle(@AuthenticationPrincipal User user) {
        return vehicleService.getActiveVehicle(user);
    }

    @GetMapping("/{id}")
    public VehicleResponseDTO getVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleService.getVehicleById(user, id);
    }

    @PutMapping("/{id}")
    public VehicleResponseDTO updateVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody VehicleRequestDTO request) {
        return vehicleService.updateVehicle(user, id, request);
    }

    @PutMapping("/{id}/odometer")
    public VehicleResponseDTO updateOdometer(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestParam Integer currentKm) {
        return vehicleService.updateOdometer(user, id, currentKm);
    }

    @PutMapping("/{id}/active")
    public void setActiveVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        vehicleService.setActiveVehicle(user, id);
    }

    @DeleteMapping("/{id}")
    public void deleteVehicle(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        vehicleService.deleteVehicle(user, id);
    }
}
