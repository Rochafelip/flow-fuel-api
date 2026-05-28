package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.common.PageResponseDTO;
import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class RefuelService {

    private final RefuelRepository refuelRepository;
    private final VehicleRepository vehicleRepository;

    public RefuelResponseDTO createRefuel(User user, RefuelRequestDTO request) {
        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", request.getVehicleId()));

        if (!ownsVehicle(user, vehicle)) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }

        Integer lastOdometer = refuelRepository
                .findTopByVehicleIdOrderByOdometerDesc(vehicle.getId())
                .map(Refuel::getOdometer)
                .orElse(vehicle.getCurrentKm());

        if (lastOdometer == null) {
            throw new BusinessRuleException(
                    "Veículo sem quilometragem inicial; defina currentKm antes de abastecer");
        }

        int newOdometer = lastOdometer + request.getTrip();

        RefuelType refuelType = resolveRefuelType(vehicle, request.getRefuelType());

        BigDecimal[] priceRange = priceRangeFor(refuelType);
        if (request.getPricePerUnit().compareTo(priceRange[0]) < 0 ||
                request.getPricePerUnit().compareTo(priceRange[1]) > 0) {
            throw new BusinessRuleException("Preço fora da faixa permitida");
        }

        validateCapacity(vehicle, refuelType, request.getEnergyAmount());

        Refuel refuel = new Refuel();
        refuel.setOdometer(newOdometer);
        refuel.setEnergyAmount(request.getEnergyAmount());
        refuel.setPricePerUnit(request.getPricePerUnit());
        refuel.setFullTank(request.getFullTank() != null ? request.getFullTank() : false);
        refuel.setKmSinceLastRefuel(request.getTrip());
        refuel.setRefuelType(refuelType);
        refuel.setVehicle(vehicle);

        Refuel saved = refuelRepository.save(refuel);

        vehicle.setCurrentKm(newOdometer);
        vehicleRepository.save(vehicle);

        return RefuelResponseDTO.from(saved);
    }

    public PageResponseDTO<RefuelResponseDTO> getVehicleRefuels(User user, Long vehicleId,
            LocalDate startDate, LocalDate endDate, Pageable pageable) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        if (!ownsVehicle(user, vehicle)) {
            throw new ForbiddenOperationException("Veículo não pertence ao usuário");
        }

        Page<Refuel> page;
        if (startDate != null && endDate != null) {
            page = refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                    vehicleId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59), pageable);
        } else {
            page = refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId, pageable);
        }

        return PageResponseDTO.from(page, RefuelResponseDTO::from);
    }

    public RefuelResponseDTO getRefuelById(User user, Long id) {
        Refuel refuel = refuelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Abastecimento", id));
        if (!ownsRefuel(user, refuel)) {
            throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
        }
        return RefuelResponseDTO.from(refuel);
    }

    public RefuelResponseDTO updateRefuel(User user, Long id, RefuelRequestDTO request) {
        Refuel refuel = refuelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Abastecimento", id));
        if (!ownsRefuel(user, refuel)) {
            throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
        }

        Vehicle vehicle = refuel.getVehicle();

        if (request.getTrip() != null) {
            Integer previousOdometer = refuelRepository
                    .findTopByVehicleIdAndOdometerLessThanOrderByOdometerDesc(
                            vehicle.getId(), refuel.getOdometer())
                    .map(Refuel::getOdometer)
                    .orElse(vehicle.getCurrentKm());

            if (previousOdometer == null) {
                throw new BusinessRuleException(
                        "Veículo sem quilometragem inicial; não é possível recalcular o odômetro");
            }

            int newOdometer = previousOdometer + request.getTrip();
            refuel.setOdometer(newOdometer);
            refuel.setKmSinceLastRefuel(request.getTrip());
        }

        if (request.getEnergyAmount() != null) {
            validateCapacity(vehicle, refuel.getRefuelType(), request.getEnergyAmount());
            refuel.setEnergyAmount(request.getEnergyAmount());
        }

        if (request.getPricePerUnit() != null) {
            BigDecimal[] priceRange = priceRangeFor(refuel.getRefuelType());
            if (request.getPricePerUnit().compareTo(priceRange[0]) < 0 ||
                    request.getPricePerUnit().compareTo(priceRange[1]) > 0) {
                throw new BusinessRuleException("Preço fora da faixa permitida");
            }
            refuel.setPricePerUnit(request.getPricePerUnit());
        }

        if (request.getFullTank() != null) refuel.setFullTank(request.getFullTank());

        return RefuelResponseDTO.from(refuelRepository.save(refuel));
    }

    public void deleteRefuel(User user, Long id) {
        Refuel refuel = refuelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Abastecimento", id));
        if (!ownsRefuel(user, refuel)) {
            throw new ForbiddenOperationException("Abastecimento não pertence ao usuário");
        }
        refuelRepository.deleteById(id);
    }

    private boolean ownsVehicle(User user, Vehicle vehicle) {
        return vehicle.getUser().getId().equals(user.getId());
    }

    private boolean ownsRefuel(User user, Refuel refuel) {
        return ownsVehicle(user, refuel.getVehicle());
    }

    private BigDecimal[] priceRangeFor(RefuelType refuelType) {
        if (refuelType == RefuelType.ELECTRIC) {
            return new BigDecimal[]{BigDecimal.valueOf(0.1), BigDecimal.valueOf(5.0)};
        }
        return new BigDecimal[]{BigDecimal.valueOf(0.5), BigDecimal.valueOf(15.0)};
    }

    private RefuelType resolveRefuelType(Vehicle vehicle, RefuelType requested) {
        RefuelType resolved = requested != null ? requested : vehicle.defaultRefuelType();
        if (resolved == null) {
            throw new BusinessRuleException(
                    "Veículo híbrido exige refuelType (FUEL ou ELECTRIC) no abastecimento");
        }
        if (!vehicle.acceptsRefuelType(resolved)) {
            throw new BusinessRuleException(
                    "Tipo de abastecimento " + resolved + " incompatível com o veículo");
        }
        return resolved;
    }

    private void validateCapacity(Vehicle vehicle, RefuelType refuelType, BigDecimal amount) {
        BigDecimal capacity = vehicle.getEffectiveCapacity(refuelType);
        if (capacity == null) {
            return;
        }
        if (amount.compareTo(capacity) > 0) {
            throw new BusinessRuleException("Quantidade de energia excede a capacidade do veículo");
        }
    }
}
