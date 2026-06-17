package com.devappmobile.flowfuel.refuel;

import com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefuelRepository extends JpaRepository<Refuel, Long> {

    List<Refuel> findByVehicleIdOrderByRefuelDateDesc(Long vehicleId);

    Page<Refuel> findByVehicleIdOrderByRefuelDateDesc(Long vehicleId, Pageable pageable);

    List<Refuel> findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
            Long vehicleId, LocalDateTime startDate, LocalDateTime endDate);

    Page<Refuel> findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
            Long vehicleId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Optional<Refuel> findTopByVehicleIdOrderByOdometerDesc(Long vehicleId);

    Optional<Refuel> findTopByVehicleIdAndOdometerLessThanOrderByOdometerDesc(
            Long vehicleId, Integer odometer);

    List<Refuel> findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(Long vehicleId);

    Optional<Refuel> findTopByVehicleIdOrderByRefuelDateDesc(Long vehicleId);

    @Query("SELECT SUM(r.totalAmount) FROM Refuel r WHERE r.vehicle.id = :vehicleId")
    Optional<BigDecimal> getTotalSpentByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("SELECT SUM(r.energyAmount) FROM Refuel r WHERE r.vehicle.id = :vehicleId")
    Optional<BigDecimal> getTotalEnergyByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("SELECT AVG(r.pricePerUnit) FROM Refuel r WHERE r.vehicle.id = :vehicleId")
    Optional<BigDecimal> getAveragePricePerUnitByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("SELECT SUM(r.totalAmount) FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType")
    Optional<BigDecimal> getTotalSpentByVehicleIdAndRefuelType(
            @Param("vehicleId") Long vehicleId,
            @Param("refuelType") RefuelType refuelType);

    @Query("SELECT SUM(r.energyAmount) FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType")
    Optional<BigDecimal> getTotalEnergyByVehicleIdAndRefuelType(
            @Param("vehicleId") Long vehicleId,
            @Param("refuelType") RefuelType refuelType);

    @Query("SELECT AVG(r.pricePerUnit) FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType")
    Optional<BigDecimal> getAveragePricePerUnitByVehicleIdAndRefuelType(
            @Param("vehicleId") Long vehicleId,
            @Param("refuelType") RefuelType refuelType);

    @Query("SELECT r FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.fullTank = true ORDER BY r.refuelDate DESC")
    List<Refuel> findFullTankRefuelsByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("SELECT r FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType AND r.fullTank = true ORDER BY r.refuelDate DESC")
    List<Refuel> findFullTankRefuelsByVehicleIdAndRefuelType(
            @Param("vehicleId") Long vehicleId,
            @Param("refuelType") RefuelType refuelType);

    @Query("SELECT COUNT(r) FROM Refuel r WHERE r.vehicle.id = :vehicleId")
    Long countByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("""
                SELECT SUM(r.totalAmount)
                FROM Refuel r
                WHERE r.vehicle.id = :vehicleId
                AND MONTH(r.refuelDate) = :month
                AND YEAR(r.refuelDate) = :year
            """)
    Optional<BigDecimal> getMonthlySpent(
            @Param("vehicleId") Long vehicleId,
            @Param("month") int month,
            @Param("year") int year);

    // ── aggregate projections (M5) ───────────────────────────────────────────

    @Query("""
            SELECT new com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection(
                COUNT(r), SUM(r.totalAmount), SUM(r.energyAmount), CAST(AVG(r.pricePerUnit) AS BigDecimal))
            FROM Refuel r WHERE r.vehicle.id = :vehicleId
            """)
    RefuelAggregateProjection getAggregatesByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("""
            SELECT new com.devappmobile.flowfuel.dashboard.RefuelAggregateProjection(
                COUNT(r), SUM(r.totalAmount), SUM(r.energyAmount), CAST(AVG(r.pricePerUnit) AS BigDecimal))
            FROM Refuel r WHERE r.vehicle.id = :vehicleId AND r.refuelType = :refuelType
            """)
    RefuelAggregateProjection getAggregatesByVehicleIdAndRefuelType(
            @Param("vehicleId") Long vehicleId,
            @Param("refuelType") RefuelType refuelType);

    // ── pageable full-tank variants (M5) ────────────────────────────────────

    Page<Refuel> findByVehicleIdAndFullTankTrueOrderByRefuelDateDesc(
            Long vehicleId, Pageable pageable);

    Page<Refuel> findByVehicleIdAndRefuelTypeAndFullTankTrueOrderByRefuelDateDesc(
            Long vehicleId, RefuelType refuelType, Pageable pageable);

}