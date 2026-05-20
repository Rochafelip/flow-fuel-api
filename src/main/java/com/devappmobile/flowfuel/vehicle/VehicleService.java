package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    public ResponseEntity<?> createVehicle(User user, VehicleRequestDTO request) {
        Vehicle vehicle = new Vehicle();
        applyRequestToVehicle(vehicle, request);
        vehicle.setUser(user);

        return ResponseEntity.ok(vehicleRepository.save(vehicle));
    }

    public ResponseEntity<List<Vehicle>> getUserVehicles(User user) {
        return ResponseEntity.ok(vehicleRepository.findByUserId(user.getId()));
    }

    public ResponseEntity<Vehicle> getActiveVehicle(User user) {
        return vehicleRepository.findByUserId(user.getId()).stream()
                .filter(Vehicle::getIsActive)
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public ResponseEntity<Vehicle> getVehicleById(User user, Long id) {
        Optional<Vehicle> vehicle = vehicleRepository.findById(id);
        if (vehicle.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsVehicle(user, vehicle.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return ResponseEntity.ok(vehicle.get());
    }

    public ResponseEntity<Vehicle> updateVehicle(User user, Long id, VehicleRequestDTO request) {
        Optional<Vehicle> vehicleOptional = vehicleRepository.findById(id);
        if (vehicleOptional.isEmpty()) return ResponseEntity.notFound().build();

        Vehicle vehicle = vehicleOptional.get();
        if (!ownsVehicle(user, vehicle)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        applyRequestToVehicle(vehicle, request);
        return ResponseEntity.ok(vehicleRepository.save(vehicle));
    }

    public ResponseEntity<Vehicle> updateOdometer(User user, Long id, Integer currentKm) {
        Optional<Vehicle> vehicleOptional = vehicleRepository.findById(id);
        if (vehicleOptional.isEmpty()) return ResponseEntity.notFound().build();

        Vehicle vehicle = vehicleOptional.get();
        if (!ownsVehicle(user, vehicle)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (currentKm < vehicle.getCurrentKm()) return ResponseEntity.badRequest().build();

        vehicle.setCurrentKm(currentKm);
        return ResponseEntity.ok(vehicleRepository.save(vehicle));
    }

    public ResponseEntity<?> setActiveVehicle(User user, Long vehicleId) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(vehicleId);
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsVehicle(user, vehicleOpt.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        List<Vehicle> userVehicles = vehicleRepository.findByUserId(user.getId());
        userVehicles.forEach(v -> v.setIsActive(v.getId().equals(vehicleId)));
        vehicleRepository.saveAll(userVehicles);

        return ResponseEntity.ok().build();
    }

    public ResponseEntity<?> deleteVehicle(User user, Long id) {
        Optional<Vehicle> vehicleOpt = vehicleRepository.findById(id);
        if (vehicleOpt.isEmpty()) return ResponseEntity.notFound().build();
        if (!ownsVehicle(user, vehicleOpt.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        vehicleRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    public String getConsumptionUnit(Vehicle vehicle) {
        return vehicle.getConsumptionUnit();
    }

    public Double calculateConsumption(Integer km, Double energy) {
        if (energy == 0) return 0.0;
        return (double) km / energy;
    }

    private boolean ownsVehicle(User user, Vehicle vehicle) {
        return vehicle.getUser().getId().equals(user.getId());
    }

    private void applyRequestToVehicle(Vehicle vehicle, VehicleRequestDTO request) {
        if (request.getType() != null) vehicle.setType(request.getType());
        if (request.getEnergyType() != null) vehicle.setEnergyType(request.getEnergyType());
        if (request.getFuelSubType() != null) vehicle.setFuelSubType(request.getFuelSubType());
        if (request.getCurrentKm() != null) vehicle.setCurrentKm(request.getCurrentKm());
        if (request.getCapacity() != null) vehicle.setCapacity(request.getCapacity());
        if (request.getBrand() != null) vehicle.setBrand(request.getBrand());
        if (request.getModel() != null) vehicle.setModel(request.getModel());
        if (request.getManufactureYear() != null) vehicle.setManufactureYear(request.getManufactureYear());
        if (request.getModelYear() != null) vehicle.setModelYear(request.getModelYear());
        if (request.getColor() != null) vehicle.setColor(request.getColor());
        if (request.getLicensePlate() != null) vehicle.setLicensePlate(request.getLicensePlate());
    }
}
