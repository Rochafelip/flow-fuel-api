package com.devappmobile.flowfuel.vehicleshare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleShareRepository extends JpaRepository<VehicleShare, Long> {

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<VehicleShareStatus> statuses);

    Optional<VehicleShare> findFirstByVehicleIdAndStatusInOrderByCreatedAtDesc(
            Long vehicleId, List<VehicleShareStatus> statuses);

    List<VehicleShare> findByGuestIdAndStatus(Long guestId, VehicleShareStatus status);

    List<VehicleShare> findByGuestIdAndStatusAndExpiresAtAfter(
            Long guestId, VehicleShareStatus status, LocalDateTime now);

    boolean existsByVehicleIdAndGuestIdAndStatusAndExpiresAtAfter(
            Long vehicleId, Long guestId, VehicleShareStatus status, LocalDateTime now);
}
