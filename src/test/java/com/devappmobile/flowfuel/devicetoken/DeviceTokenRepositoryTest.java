package com.devappmobile.flowfuel.devicetoken;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DeviceTokenRepositoryTest {

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void save_novoToken_persisteEEncontraPorId() {
        User user = new User();
        user.setEmail("device-token-repo-test@test.com");
        user.setPassword("hash");
        user.setName("User");
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setToken("fcm-token-abc");
        deviceToken.setUser(user);
        deviceToken.setPlatform(DevicePlatform.ANDROID);
        deviceTokenRepository.save(deviceToken);

        Optional<DeviceToken> found = deviceTokenRepository.findById("fcm-token-abc");

        assertThat(found).isPresent();
        assertThat(found.get().getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
    }
}
