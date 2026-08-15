package com.devappmobile.flowfuel.vehicleevent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleEventRepository extends JpaRepository<VehicleEvent, Long> {

    @Query("SELECT SUM(e.amount) FROM VehicleEvent e WHERE e.vehicle.id = :vehicleId")
    Optional<BigDecimal> getTotalAmountByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("""
                SELECT SUM(e.amount)
                FROM VehicleEvent e
                WHERE e.vehicle.id = :vehicleId
                AND MONTH(e.eventDate) = :month
                AND YEAR(e.eventDate) = :year
            """)
    Optional<BigDecimal> getMonthlySpent(
            @Param("vehicleId") Long vehicleId,
            @Param("month") int month,
            @Param("year") int year);

    @Query("SELECT new com.devappmobile.flowfuel.vehicleevent.VehicleEventTypeAmountProjection(e.type, SUM(e.amount)) "
            + "FROM VehicleEvent e WHERE e.vehicle.id = :vehicleId GROUP BY e.type")
    List<VehicleEventTypeAmountProjection> getTotalAmountByVehicleIdGroupedByType(
            @Param("vehicleId") Long vehicleId);

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

    List<VehicleEvent> findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId);

    List<VehicleEvent> findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type);

    List<VehicleEvent> findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            LocalDate startDate,
            LocalDate endDate);

    List<VehicleEvent> findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type,
            LocalDate startDate,
            LocalDate endDate);
}
