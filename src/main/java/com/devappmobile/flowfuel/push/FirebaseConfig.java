package com.devappmobile.flowfuel.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
@ConditionalOnProperty(name = "flowfuel.push.enabled", havingValue = "true")
public class FirebaseConfig {

    @Value("${flowfuel.push.credentials-file:}")
    private String credentialsFile;

    @Value("${flowfuel.push.credentials-json:}")
    private String credentialsJson;

    @Bean
    public FirebaseMessaging firebaseMessaging() throws IOException {
        try (InputStream serviceAccount = credentialsJson.isBlank()
                ? new FileInputStream(credentialsFile)
                : new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            return FirebaseMessaging.getInstance(app);
        }
    }
}
