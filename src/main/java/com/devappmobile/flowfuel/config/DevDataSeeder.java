package com.devappmobile.flowfuel.config;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String SEED_EMAIL = "dev@flowfuel.local";
    private static final String SEED_PASSWORD = "Dev@12345";
    private static final String SEED_NAME = "Dev User";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(SEED_EMAIL)) {
            return;
        }
        User user = new User(SEED_EMAIL, passwordEncoder.encode(SEED_PASSWORD), SEED_NAME);
        userRepository.save(user);
        log.info("[DevDataSeeder] Usuario seed criado -> email={} senha={}", SEED_EMAIL, SEED_PASSWORD);
    }
}
