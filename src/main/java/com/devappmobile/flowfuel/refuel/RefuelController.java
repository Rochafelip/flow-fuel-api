package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/refuels")
@RequiredArgsConstructor
public class RefuelController {

    private final RefuelService refuelService;

    @PostMapping
    public RefuelResponseDTO createRefuel(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RefuelRequestDTO request) {
        return refuelService.createRefuel(user, request);
    }

    @GetMapping("/vehicle/{vehicleId}")
    public PageResponseDTO<RefuelResponseDTO> getVehicleRefuels(
            @AuthenticationPrincipal User user,
            @PathVariable Long vehicleId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @PageableDefault(size = 20) Pageable pageable) {
        return refuelService.getVehicleRefuels(user, vehicleId, startDate, endDate, pageable);
    }

    @GetMapping("/{id}")
    public RefuelResponseDTO getRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        return refuelService.getRefuelById(user, id);
    }

    @PutMapping("/{id}")
    public RefuelResponseDTO updateRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @Valid @RequestBody RefuelRequestDTO request) {
        return refuelService.updateRefuel(user, id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteRefuel(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        refuelService.deleteRefuel(user, id);
    }
}
