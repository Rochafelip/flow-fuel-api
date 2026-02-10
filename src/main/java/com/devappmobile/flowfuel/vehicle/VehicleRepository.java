package com.devappmobile.flowfuel.vehicle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    
    List<Vehicle> findByUserId(Long userId);
    
    @Query("SELECT COUNT(v) > 0 FROM Vehicle v WHERE v.licensePlate = :licensePlate AND v.user.id = :userId")
    boolean existsByLicensePlateAndUserId(@Param("licensePlate") String licensePlate, 
                                         @Param("userId") Long userId);
    
    List<Vehicle> findByUserIdAndIsActiveTrue(Long userId);
    
    boolean existsById(Long id);
}