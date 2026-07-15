package com.devappmobile.flowfuel.vehicleshare;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareRequestDTO;
import com.devappmobile.flowfuel.vehicleshare.dto.VehicleShareResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vehicle-shares")
@RequiredArgsConstructor
public class VehicleShareController {

    private final VehicleShareService vehicleShareService;

    @PostMapping
    public VehicleShareResponseDTO create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VehicleShareRequestDTO request) {
        return vehicleShareService.create(user, request);
    }

    @PostMapping("/{id}/accept")
    public VehicleShareResponseDTO accept(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return vehicleShareService.accept(user, id);
    }

    @PostMapping("/{id}/reject")
    public VehicleShareResponseDTO reject(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return vehicleShareService.reject(user, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal User user, @PathVariable Long id) {
        vehicleShareService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<VehicleShareResponseDTO> getForVehicle(
            @AuthenticationPrincipal User user, @PathVariable Long vehicleId) {
        VehicleShareResponseDTO dto = vehicleShareService.getForVehicle(user, vehicleId);
        return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.noContent().build();
    }

    @GetMapping("/pending")
    public List<VehicleShareResponseDTO> listPending(@AuthenticationPrincipal User user) {
        return vehicleShareService.listPendingForGuest(user);
    }

    @GetMapping("/active-for-me")
    public List<VehicleShareResponseDTO> listActiveForMe(@AuthenticationPrincipal User user) {
        return vehicleShareService.listActiveForGuest(user);
    }
}
