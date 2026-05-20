package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/refuels")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class RefuelController {

    private final RefuelService refuelService;

    @PostMapping
    public ResponseEntity<Refuel> createRefuel(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RefuelRequestDTO request) {
        return refuelService.createRefuel(user, request);
    }

    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<Refuel>> getVehicleRefuels(
            @AuthenticationPrincipal User user,
            @PathVariable Long vehicleId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return refuelService.getVehicleRefuels(user, vehicleId, startDate, endDate);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Refuel> getRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return refuelService.getRefuelById(user, id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Refuel> updateRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody RefuelRequestDTO request) {
        return refuelService.updateRefuel(user, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return refuelService.deleteRefuel(user, id);
    }
}
