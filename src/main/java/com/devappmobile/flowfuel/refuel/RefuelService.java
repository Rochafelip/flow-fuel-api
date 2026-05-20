package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    public ResponseEntity<Refuel> createRefuel(User user, RefuelRequestDTO request) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(request.getVehicleId());
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();

        Vehicle vehicle = vehicleOpt.get();
        if (!ownsVehicle(user, vehicle)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        Integer lastOdometer = refuelRepository
                .findTopByVehicleIdOrderByOdometerDesc(vehicle.getId())
                .map(Refuel::getOdometer)
                .orElse(vehicle.getCurrentKm());

        if (request.getOdometer() < lastOdometer) return ResponseEntity.badRequest().build();

        BigDecimal[] priceRange = priceRangeFor(vehicle);
        if (request.getPricePerUnit().compareTo(priceRange[0]) < 0 ||
                request.getPricePerUnit().compareTo(priceRange[1]) > 0) {
            return ResponseEntity.badRequest().build();
        }

        if (request.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.getCapacity())) > 0) {
            return ResponseEntity.badRequest().build();
        }

        Refuel refuel = new Refuel();
        refuel.setOdometer(request.getOdometer());
        refuel.setEnergyAmount(request.getEnergyAmount());
        refuel.setPricePerUnit(request.getPricePerUnit());
        refuel.setFullTank(request.getFullTank() != null ? request.getFullTank() : false);
        refuel.setKmSinceLastRefuel(request.getOdometer() - lastOdometer);
        refuel.setVehicle(vehicle);

        Refuel saved = refuelRepository.save(refuel);

        vehicle.setCurrentKm(request.getOdometer());
        vehicleRepository.save(vehicle);

        return ResponseEntity.ok(saved);
    }

    public ResponseEntity<List<Refuel>> getVehicleRefuels(User user, Long vehicleId,
            LocalDate startDate, LocalDate endDate) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(vehicleId);
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsVehicle(user, vehicleOpt.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        List<Refuel> refuels;
        if (startDate != null && endDate != null) {
            refuels = refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                    vehicleId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        } else {
            refuels = refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId);
        }

        return ResponseEntity.ok(refuels);
    }

    public ResponseEntity<Refuel> getRefuelById(User user, Long id) {
        Optional<Refuel> refuelOpt = refuelRepository.findById(id);
        if (refuelOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsRefuel(user, refuelOpt.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(refuelOpt.get());
    }

    public ResponseEntity<Refuel> updateRefuel(User user, Long id, RefuelRequestDTO request) {
        Optional<Refuel> refuelOpt = refuelRepository.findById(id);
        if (refuelOpt.isEmpty()) return ResponseEntity.notFound().build();

        Refuel refuel = refuelOpt.get();
        if (!ownsRefuel(user, refuel)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        Vehicle vehicle = refuel.getVehicle();

        if (request.getOdometer() != null) {
            Integer previousOdometer = refuelRepository
                    .findTopByVehicleIdAndOdometerLessThanOrderByOdometerDesc(
                            vehicle.getId(), refuel.getOdometer())
                    .map(Refuel::getOdometer)
                    .orElse(vehicle.getCurrentKm());

            if (request.getOdometer() < previousOdometer) return ResponseEntity.badRequest().build();

            refuel.setOdometer(request.getOdometer());
            refuel.setKmSinceLastRefuel(request.getOdometer() - previousOdometer);
        }

        if (request.getEnergyAmount() != null) {
            if (request.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.getCapacity())) > 0) {
                return ResponseEntity.badRequest().build();
            }
            refuel.setEnergyAmount(request.getEnergyAmount());
        }

        if (request.getPricePerUnit() != null) {
            BigDecimal[] priceRange = priceRangeFor(vehicle);
            if (request.getPricePerUnit().compareTo(priceRange[0]) < 0 ||
                    request.getPricePerUnit().compareTo(priceRange[1]) > 0) {
                return ResponseEntity.badRequest().build();
            }
            refuel.setPricePerUnit(request.getPricePerUnit());
        }

        if (request.getFullTank() != null) refuel.setFullTank(request.getFullTank());

        return ResponseEntity.ok(refuelRepository.save(refuel));
    }

    public ResponseEntity<?> deleteRefuel(User user, Long id) {
        Optional<Refuel> refuelOpt = refuelRepository.findById(id);
        if (refuelOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsRefuel(user, refuelOpt.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        refuelRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    public ResponseEntity<BigDecimal> calculateAverageConsumption(Long vehicleId) {
        List<Refuel> refuels = refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(vehicleId);

        if (refuels.size() < 2) return ResponseEntity.ok(BigDecimal.ZERO);

        double totalKm = 0;
        double totalEnergy = 0;
        int validRefuels = 0;

        for (int i = 0; i < refuels.size() - 1; i++) {
            Refuel current = refuels.get(i);
            if (current.getKmSinceLastRefuel() != null && current.getKmSinceLastRefuel() > 0 &&
                    current.getEnergyAmount() != null &&
                    current.getEnergyAmount().compareTo(BigDecimal.ZERO) > 0) {
                totalKm += current.getKmSinceLastRefuel();
                totalEnergy += current.getEnergyAmount().doubleValue();
                validRefuels++;
            }
        }

        if (validRefuels == 0 || totalEnergy == 0) return ResponseEntity.ok(BigDecimal.ZERO);

        return ResponseEntity.ok(BigDecimal.valueOf(totalKm / totalEnergy)
                .setScale(2, RoundingMode.HALF_UP));
    }

    public ResponseEntity<BigDecimal> getTotalSpent(Long vehicleId) {
        return ResponseEntity.ok(refuelRepository.getTotalSpentByVehicleId(vehicleId).orElse(BigDecimal.ZERO));
    }

    public ResponseEntity<BigDecimal> getTotalEnergy(Long vehicleId) {
        return ResponseEntity.ok(refuelRepository.getTotalEnergyByVehicleId(vehicleId).orElse(BigDecimal.ZERO));
    }

    public ResponseEntity<BigDecimal> getAveragePricePerUnit(Long vehicleId) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(vehicleId);
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();

        Optional<BigDecimal> totalEnergy = refuelRepository.getTotalEnergyByVehicleId(vehicleId);
        Optional<BigDecimal> totalAmount = refuelRepository.getTotalSpentByVehicleId(vehicleId);

        if (totalEnergy.isPresent() && totalAmount.isPresent() &&
                totalEnergy.get().compareTo(BigDecimal.ZERO) > 0) {
            return ResponseEntity.ok(totalAmount.get().divide(totalEnergy.get(), 3, RoundingMode.HALF_UP));
        }

        return ResponseEntity.ok(BigDecimal.ZERO);
    }

    private boolean ownsVehicle(User user, Vehicle vehicle) {
        return vehicle.getUser().getId().equals(user.getId());
    }

    private boolean ownsRefuel(User user, Refuel refuel) {
        return ownsVehicle(user, refuel.getVehicle());
    }

    private BigDecimal[] priceRangeFor(Vehicle vehicle) {
        if (vehicle.isElectric()) {
            return new BigDecimal[]{BigDecimal.valueOf(0.1), BigDecimal.valueOf(5.0)};
        }
        return new BigDecimal[]{BigDecimal.valueOf(0.5), BigDecimal.valueOf(15.0)};
    }
}
