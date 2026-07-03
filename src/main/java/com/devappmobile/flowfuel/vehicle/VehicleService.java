package com.devappmobile.flowfuel.vehicle;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.storage.StorageService;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.dto.PhotoUploadResponse;
import com.devappmobile.flowfuel.vehicle.dto.VehicleRequestDTO;
import com.devappmobile.flowfuel.vehicle.dto.VehicleResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final AuthorizationHelper authorizationHelper;
    private final StorageService storageService;

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

    public PhotoUploadResponse uploadPhoto(User user, Long id, MultipartFile file) {
        Vehicle vehicle = findOwned(user, id);

        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Arquivo não informado");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessRuleException("Tipo de arquivo inválido. Permitido: JPEG, PNG, WEBP");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessRuleException("Arquivo excede o tamanho máximo de 5 MB");
        }

        String previousKey = vehicle.getPhoto();
        if (previousKey != null) {
            try {
                storageService.delete(previousKey);
            } catch (Exception ignored) {
            }
        }

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "photo";

        String key = "vehicle_photos/" + id + "_" + originalName;
        storageService.upload(file, key);
        vehicle.setPhoto(key);
        vehicleRepository.save(vehicle);

        return new PhotoUploadResponse("/vehicles/" + id + "/photo");
    }

    public ResponseEntity<byte[]> getPhoto(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        String key = vehicle.getPhoto();
        if (key == null) {
            return ResponseEntity.noContent().build();
        }

        StorageService.StorageObject obj = storageService.download(key);
        return ResponseEntity.ok()
                .header("Content-Type", obj.contentType())
                .body(obj.data());
    }

    public void removePhoto(User user, Long id) {
        Vehicle vehicle = findOwned(user, id);
        String key = vehicle.getPhoto();
        if (key != null) {
            storageService.delete(key);
            vehicle.setPhoto(null);
            vehicleRepository.save(vehicle);
        }
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
