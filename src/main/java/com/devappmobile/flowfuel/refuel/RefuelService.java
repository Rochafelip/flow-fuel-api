package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefuelService {
    
    private final RefuelRepository refuelRepository;
    private final VehicleRepository vehicleRepository;
    
    public ResponseEntity<Refuel> createRefuel(Long vehicleId, Refuel refuel) {
        Optional<Vehicle> vehicle = vehicleRepository.findById(vehicleId);
        if (vehicle.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        if (refuel.getOdometer() == null || refuel.getEnergyAmount() == null || 
            refuel.getPricePerUnit() == null) {
            return ResponseEntity.badRequest().build();
        }

        if (refuel.getRefuelDate() != null && refuel.getRefuelDate().isAfter(LocalDateTime.now())) {
            return ResponseEntity.badRequest().build();
        }
        
        Integer lastOdometer = refuelRepository.findTopByVehicleIdOrderByOdometerDesc(vehicleId)
                .map(Refuel::getOdometer)
                .orElse(vehicle.get().getCurrentKm());
        
        if (refuel.getOdometer() < lastOdometer) {
            return ResponseEntity.badRequest().build();
        }
        
        BigDecimal minPrice = vehicle.get().isElectric() ? BigDecimal.valueOf(0.1) : BigDecimal.valueOf(0.5);
        BigDecimal maxPrice = vehicle.get().isElectric() ? BigDecimal.valueOf(2.0) : BigDecimal.valueOf(10.0);
        
        if (refuel.getPricePerUnit().compareTo(minPrice) < 0 || 
            refuel.getPricePerUnit().compareTo(maxPrice) > 0) {
            return ResponseEntity.badRequest().build();
        }
        
        if (refuel.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.get().getCapacity())) > 0) {
            return ResponseEntity.badRequest().build();
        }
        
        refuel.setKmSinceLastRefuel(refuel.getOdometer() - lastOdometer);
        refuel.setVehicle(vehicle.get());
        
        Refuel savedRefuel = refuelRepository.save(refuel);
        
        vehicle.get().setCurrentKm(refuel.getOdometer());
        vehicleRepository.save(vehicle.get());
        
        return ResponseEntity.ok(savedRefuel);
    }
    
    public ResponseEntity<List<Refuel>> getVehicleRefuels(Long vehicleId, LocalDate startDate, LocalDate endDate) {
        if (!vehicleRepository.existsById(vehicleId)) {
            return ResponseEntity.notFound().build();
        }
        
        List<Refuel> refuels;
        
        if (startDate != null && endDate != null) {
            LocalDateTime startDateTime = startDate.atStartOfDay();
            LocalDateTime endDateTime = endDate.atTime(23, 59, 59);
            refuels = refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                vehicleId, startDateTime, endDateTime);
        } else {
            refuels = refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId);
        }
        
        return ResponseEntity.ok(refuels);
    }
    
    public ResponseEntity<Refuel> getRefuelById(Long id) {
        Optional<Refuel> refuel = refuelRepository.findById(id);
        return refuel.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
    }
    
    public ResponseEntity<Refuel> updateRefuel(Long id, Refuel refuelDetails) {
        Optional<Refuel> refuelOptional = refuelRepository.findById(id);
        if (refuelOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Refuel refuel = refuelOptional.get();
        Vehicle vehicle = refuel.getVehicle();
        
        if (refuelDetails.getOdometer() != null) {
            Integer previousOdometer = refuelRepository
                .findTopByVehicleIdAndOdometerLessThanOrderByOdometerDesc(
                    vehicle.getId(), refuel.getOdometer())
                .map(Refuel::getOdometer)
                .orElse(vehicle.getCurrentKm());
            
            if (refuelDetails.getOdometer() < previousOdometer) {
                return ResponseEntity.badRequest().build();
            }
            refuel.setOdometer(refuelDetails.getOdometer());
            refuel.setKmSinceLastRefuel(refuelDetails.getOdometer() - previousOdometer);
        }
        
        if (refuelDetails.getEnergyAmount() != null) {
            if (refuelDetails.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.getCapacity())) > 0) {
                return ResponseEntity.badRequest().build();
            }
            refuel.setEnergyAmount(refuelDetails.getEnergyAmount());
        }
        
        if (refuelDetails.getPricePerUnit() != null) {
            BigDecimal minPrice = vehicle.isElectric() ? BigDecimal.valueOf(0.1) : BigDecimal.valueOf(0.5);
            BigDecimal maxPrice = vehicle.isElectric() ? BigDecimal.valueOf(2.0) : BigDecimal.valueOf(10.0);
            
            if (refuelDetails.getPricePerUnit().compareTo(minPrice) < 0 || 
                refuelDetails.getPricePerUnit().compareTo(maxPrice) > 0) {
                return ResponseEntity.badRequest().build();
            }
            refuel.setPricePerUnit(refuelDetails.getPricePerUnit());
        }
        
        if (refuelDetails.getFullTank() != null) {
            refuel.setFullTank(refuelDetails.getFullTank());
        }
        
        Refuel updatedRefuel = refuelRepository.save(refuel);
        return ResponseEntity.ok(updatedRefuel);
    }
    
    public ResponseEntity<?> deleteRefuel(Long id) {
        if (!refuelRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        
        refuelRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
    
    public ResponseEntity<BigDecimal> calculateAverageConsumption(Long vehicleId) {
        List<Refuel> refuels = refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(vehicleId);
        
        if (refuels.size() < 2) {
            return ResponseEntity.ok(BigDecimal.ZERO);
        }
        
        double totalKm = 0;
        double totalEnergy = 0;
        int validRefuels = 0;
        
        for (int i = 0; i < refuels.size() - 1; i++) {
            Refuel current = refuels.get(i);
            Refuel previous = refuels.get(i + 1);
            
            if (current.getKmSinceLastRefuel() != null && 
                current.getKmSinceLastRefuel() > 0 &&
                current.getEnergyAmount() != null &&
                current.getEnergyAmount().compareTo(BigDecimal.ZERO) > 0) {
                
                totalKm += current.getKmSinceLastRefuel();
                totalEnergy += current.getEnergyAmount().doubleValue();
                validRefuels++;
            }
        }
        
        if (validRefuels == 0 || totalEnergy == 0) {
            return ResponseEntity.ok(BigDecimal.ZERO);
        }
        
        BigDecimal averageConsumption = BigDecimal.valueOf(totalKm / totalEnergy);
        return ResponseEntity.ok(averageConsumption.setScale(2, RoundingMode.HALF_UP));
    }
    
    public ResponseEntity<BigDecimal> getTotalSpent(Long vehicleId) {
        Optional<BigDecimal> totalOpt = refuelRepository.getTotalSpentByVehicleId(vehicleId);
        BigDecimal total = totalOpt.orElse(BigDecimal.ZERO);
        return ResponseEntity.ok(total);
    }
    
    public ResponseEntity<BigDecimal> getTotalEnergy(Long vehicleId) {
        Optional<BigDecimal> energyOpt = refuelRepository.getTotalEnergyByVehicleId(vehicleId);
        BigDecimal energy = energyOpt.orElse(BigDecimal.ZERO);
        return ResponseEntity.ok(energy);
    }
    
    public ResponseEntity<BigDecimal> getAveragePricePerUnit(Long vehicleId) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(vehicleId);
        if (vehicleOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Optional<BigDecimal> totalEnergyOpt = refuelRepository.getTotalEnergyByVehicleId(vehicleId);
        Optional<BigDecimal> totalAmountOpt = refuelRepository.getTotalSpentByVehicleId(vehicleId);
        
        if (totalEnergyOpt.isPresent() && totalAmountOpt.isPresent() && 
            totalEnergyOpt.get().compareTo(BigDecimal.ZERO) > 0) {
            
            BigDecimal average = totalAmountOpt.get().divide(totalEnergyOpt.get(), 3, RoundingMode.HALF_UP);
            return ResponseEntity.ok(average);
        }
        
        return ResponseEntity.ok(BigDecimal.ZERO);
    }
}