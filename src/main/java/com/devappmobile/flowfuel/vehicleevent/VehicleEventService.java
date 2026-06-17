package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventRequestDTO;
import com.devappmobile.flowfuel.vehicleevent.dto.VehicleEventResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class VehicleEventService {

    private final VehicleEventRepository vehicleEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthorizationHelper authorizationHelper;

    public VehicleEventResponseDTO create(User user, VehicleEventRequestDTO request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.getVehicleId()));

        authorizationHelper.ensureOwnsVehicle(user, vehicle);

        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(request.getType());
        event.setAmount(request.getAmount());
        event.setEventDate(request.getEventDate());
        event.setOdometer(request.getOdometer());
        event.setDescription(request.getDescription());

        return VehicleEventResponseDTO.from(vehicleEventRepository.save(event));
    }

    public VehicleEventResponseDTO getById(User user, Long id) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);
        return VehicleEventResponseDTO.from(event);
    }

    public VehicleEventResponseDTO update(User user, Long id, VehicleEventRequestDTO request) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);

        if (request.getType() != null) event.setType(request.getType());
        if (request.getAmount() != null) event.setAmount(request.getAmount());
        if (request.getDescription() != null) event.setDescription(request.getDescription());
        if (request.getOdometer() != null) event.setOdometer(request.getOdometer());
        if (request.getEventDate() != null) event.setEventDate(request.getEventDate());

        return VehicleEventResponseDTO.from(vehicleEventRepository.save(event));
    }

    public void delete(User user, Long id) {
        VehicleEvent event = vehicleEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evento", id));
        authorizationHelper.ensureOwnsEvent(user, event);
        vehicleEventRepository.deleteById(id);
    }

    public PageResponseDTO<VehicleEventResponseDTO> getVehicleEvents(User user, Long vehicleId,
            VehicleEventType type, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);

        Page<VehicleEvent> page;
        if (type != null && startDate != null && endDate != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, type, startDate, endDate, pageable);
        } else if (type != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, type, pageable);
        } else if (startDate != null && endDate != null) {
            page = vehicleEventRepository
                    .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, startDate, endDate, pageable);
        } else {
            page = vehicleEventRepository
                    .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(vehicleId, pageable);
        }

        return PageResponseDTO.from(page, VehicleEventResponseDTO::from);
    }

}
