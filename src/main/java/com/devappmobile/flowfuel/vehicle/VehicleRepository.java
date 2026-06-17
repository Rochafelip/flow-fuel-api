package com.devappmobile.flowfuel.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByUserId(Long userId);

    Page<Vehicle> findByUserId(Long userId, Pageable pageable);

    List<Vehicle> findByUserIdAndIsActiveTrue(Long userId);
}
