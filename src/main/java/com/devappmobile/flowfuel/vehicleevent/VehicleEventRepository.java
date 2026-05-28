package com.devappmobile.flowfuel.vehicleevent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface VehicleEventRepository extends JpaRepository<VehicleEvent, Long> {

    Page<VehicleEvent> findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            Pageable pageable);

    Page<VehicleEvent> findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type,
            Pageable pageable);

    Page<VehicleEvent> findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable);

    Page<VehicleEvent> findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable);
}
