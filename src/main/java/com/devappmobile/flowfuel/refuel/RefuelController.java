package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.refuel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/refuels")
@CrossOrigin(origins = "*")
public class RefuelController {
    
    @Autowired
    private RefuelService refuelService;
    
    @PostMapping
    public ResponseEntity<Refuel> createRefuel(
            @RequestParam Long vehicleId, 
            @RequestBody Refuel refuel) {
        // RF003.1 - Registrar abastecimento
        return refuelService.createRefuel(vehicleId, refuel);
    }
    
    @GetMapping("/vehicle/{vehicleId}")
    public ResponseEntity<List<Refuel>> getVehicleRefuels(
            @PathVariable Long vehicleId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        // RF003.3 - Histórico com filtro
        return refuelService.getVehicleRefuels(vehicleId, startDate, endDate);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<Refuel> getRefuel(@PathVariable Long id) {
        return refuelService.getRefuelById(id);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<Refuel> updateRefuel(
            @PathVariable Long id, 
            @RequestBody Refuel refuel) {
        // RF003.3 - Editar registro
        return refuelService.updateRefuel(id, refuel);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRefuel(@PathVariable Long id) {
        return refuelService.deleteRefuel(id);
    }
}
