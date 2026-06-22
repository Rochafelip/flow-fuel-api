package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import com.devappmobile.flowfuel.vehicle.dto.VehicleResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final AuthorizationHelper authorizationHelper;

    public VehicleResponseDTO createVehicle(User user, VehicleRequestDTO request) {
        Vehicle vehicle = new Vehicle();
        applyRequestToVehicle(vehicle, request);
        vehicle.setUser(user);
        return VehicleResponseDTO.from(vehicleRepository.save(vehicle));
    }

    public PageResponseDTO<VehicleResponseDTO> getUserVehicles(User user, Pageable pageable) {
        return PageResponseDTO.from(
                vehicleRepository.findByUserId(user.getId(), pageable),
                VehicleResponseDTO::from);
    }

    public VehicleResponseDTO getActiveVehicle(User user) {
        return vehicleRepository.findByUserId(user.getId()).stream()
                .filter(Vehicle::getIsActive)
                .findFirst()
                .map(VehicleResponseDTO::from)
                .orElseThrow(() -> new ResourceNotFoundException("Nenhum veículo ativo encontrado"));
    }

    public VehicleResponseDTO getVehicleById(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        return VehicleResponseDTO.from(vehicle);
    }

    public VehicleResponseDTO updateVehicle(User user, Long id, VehicleRequestDTO request) {
        Vehicle vehicle = findOwned(user, id);
        applyRequestToVehicle(vehicle, request);
        return VehicleResponseDTO.from(vehicleRepository.save(vehicle));
    }

    public VehicleResponseDTO updateOdometer(User user, Long id, Integer currentKm) {
        Vehicle vehicle = findOwned(user, id);
        if (currentKm < vehicle.getCurrentKm()) {
            throw new BusinessRuleException("Odômetro não pode ser menor que o atual");
        }
        vehicle.setCurrentKm(currentKm);
        return VehicleResponseDTO.from(vehicleRepository.save(vehicle));
    }

    @Transactional
    public void setActiveVehicle(User user, Long vehicleId) {
        findOwned(user, vehicleId);

        List<Vehicle> userVehicles = vehicleRepository.findByUserId(user.getId());
        userVehicles.forEach(v -> v.setIsActive(v.getId().equals(vehicleId)));
        vehicleRepository.saveAll(userVehicles);
    }

    public void deleteVehicle(User user, Long id) {
        findOwned(user, id);
        vehicleRepository.deleteById(id);
    }

    public String getConsumptionUnit(Vehicle vehicle) {
        return vehicle.getConsumptionUnit();
    }

    public Double calculateConsumption(Integer km, Double energy) {
        if (energy == 0) return 0.0;
        return (double) km / energy;
    }

    private Vehicle findOwned(User user, Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", id));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);
        return vehicle;
    }

    private void applyRequestToVehicle(Vehicle vehicle, VehicleRequestDTO request) {
        if (request.getType() != null) vehicle.setType(request.getType());
        if (request.getEnergyType() != null) vehicle.setEnergyType(request.getEnergyType());
        if (request.getFuelSubType() != null) vehicle.setFuelSubType(request.getFuelSubType());
        if (request.getCurrentKm() != null) vehicle.setCurrentKm(request.getCurrentKm());
        if (request.getCapacity() != null) vehicle.setCapacity(request.getCapacity());
        if (request.getBatteryCapacity() != null) vehicle.setBatteryCapacity(request.getBatteryCapacity());
        if (request.getBrand() != null) vehicle.setBrand(request.getBrand());
        if (request.getModel() != null) vehicle.setModel(request.getModel());
        if (request.getManufactureYear() != null) vehicle.setManufactureYear(request.getManufactureYear());
        if (request.getModelYear() != null) vehicle.setModelYear(request.getModelYear());
        if (request.getColor() != null) vehicle.setColor(request.getColor());
        if (request.getLicensePlate() != null) vehicle.setLicensePlate(request.getLicensePlate());
    }
}
