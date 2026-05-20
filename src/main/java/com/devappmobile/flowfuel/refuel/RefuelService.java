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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

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

        if (request.getOdometer() < lastOdometer) {
            throw new BusinessRuleException("Odômetro não pode ser menor que o último registrado");
        }

        BigDecimal[] priceRange = priceRangeFor(vehicle);
        if (request.getPricePerUnit().compareTo(priceRange[0]) < 0 ||
                request.getPricePerUnit().compareTo(priceRange[1]) > 0) {
            throw new BusinessRuleException("Preço fora da faixa permitida");
        }

        if (request.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.getCapacity())) > 0) {
            throw new BusinessRuleException("Quantidade de energia excede a capacidade do veículo");
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

        if (request.getOdometer() != null) {
            Integer previousOdometer = refuelRepository
                    .findTopByVehicleIdAndOdometerLessThanOrderByOdometerDesc(
                            vehicle.getId(), refuel.getOdometer())
                    .map(Refuel::getOdometer)
                    .orElse(vehicle.getCurrentKm());

            if (request.getOdometer() < previousOdometer) {
                throw new BusinessRuleException("Odômetro não pode ser menor que o anterior");
            }

            refuel.setOdometer(request.getOdometer());
            refuel.setKmSinceLastRefuel(request.getOdometer() - previousOdometer);
        }

        if (request.getEnergyAmount() != null) {
            if (request.getEnergyAmount().compareTo(BigDecimal.valueOf(vehicle.getCapacity())) > 0) {
                throw new BusinessRuleException("Quantidade de energia excede a capacidade do veículo");
            }
            refuel.setEnergyAmount(request.getEnergyAmount());
        }

        if (request.getPricePerUnit() != null) {
            BigDecimal[] priceRange = priceRangeFor(vehicle);
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

    public BigDecimal calculateAverageConsumption(Long vehicleId) {
        List<Refuel> refuels = refuelRepository.findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(vehicleId);

        if (refuels.size() < 2) return BigDecimal.ZERO;

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

        if (validRefuels == 0 || totalEnergy == 0) return BigDecimal.ZERO;

        return BigDecimal.valueOf(totalKm / totalEnergy).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getTotalSpent(Long vehicleId) {
        return refuelRepository.getTotalSpentByVehicleId(vehicleId).orElse(BigDecimal.ZERO);
    }

    public BigDecimal getTotalEnergy(Long vehicleId) {
        return refuelRepository.getTotalEnergyByVehicleId(vehicleId).orElse(BigDecimal.ZERO);
    }

    public BigDecimal getAveragePricePerUnit(Long vehicleId) {
        vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));

        BigDecimal totalEnergy = refuelRepository.getTotalEnergyByVehicleId(vehicleId).orElse(BigDecimal.ZERO);
        BigDecimal totalAmount = refuelRepository.getTotalSpentByVehicleId(vehicleId).orElse(BigDecimal.ZERO);

        if (totalEnergy.compareTo(BigDecimal.ZERO) > 0) {
            return totalAmount.divide(totalEnergy, 3, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
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
