package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventRequestDTO;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/vehicle-events")
@RequiredArgsConstructor
public class VehicleEventController {

    private final VehicleEventService vehicleEventService;

    @PostMapping
    public VehicleEventResponseDTO createVehicleEvent(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VehicleEventRequestDTO request) {
        return vehicleEventService.create(user, request);
    }

    @GetMapping("/vehicle/{vehicleId}")
    public PageResponseDTO<VehicleEventResponseDTO> getVehicleEvents(
            @AuthenticationPrincipal User user,
            @PathVariable Long vehicleId,
            @RequestParam(required = false) VehicleEventType type,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable) {
        return vehicleEventService.getVehicleEvents(user, vehicleId, type, startDate, endDate, pageable);
    }

    @GetMapping("/{id}")
    public VehicleEventResponseDTO getVehicleEvent(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return vehicleEventService.getById(user, id);
    }

    @PutMapping("/{id}")
    public VehicleEventResponseDTO updateVehicleEvent(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody VehicleEventRequestDTO request) {
        return vehicleEventService.update(user, id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteVehicleEvent(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        vehicleEventService.delete(user, id);
    }
}
