package com.devappmobile.flowfuel.devicetoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {

    List<DeviceToken> findByUserId(Long userId);
}
