package com.devappmobile.flowfuel.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class R2Config {

    @Value("${flowfuel.storage.r2.account-id:}")
    private String accountId;

    @Value("${flowfuel.storage.r2.access-key-id:}")
    private String accessKeyId;

    @Value("${flowfuel.storage.r2.secret-access-key:}")
    private String secretAccessKey;

    // Bean incondicional (sem @ConditionalOnProperty): R2StorageService (Task 4) injeta este
    // S3Client via construtor em todo contexto Spring, inclusive @SpringBootTest com credenciais
    // em branco. validateCredentials(false) acima existe justamente para permitir a construcao
    // do client nesses casos sem lancar erro.
    @Bean
    public S3Client r2S3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.builder()
                                .accessKeyId(accessKeyId)
                                .secretAccessKey(secretAccessKey)
                                .validateCredentials(false)
                                .build()))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
