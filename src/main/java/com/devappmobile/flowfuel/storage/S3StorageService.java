package com.devappmobile.flowfuel.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Presigner;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import java.time.Duration;
import java.net.URI;

@Service
public class S3StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private S3Client s3;
    private S3Presigner presigner;

    @Value("${B2_S3_ENDPOINT:}")
    private String endpoint;

    @Value("${B2_S3_REGION:us-west-002}")
    private String region;

    @Value("${B2_S3_ACCESS_KEY:}")
    private String accessKey;

    @Value("${B2_S3_SECRET:}")
    private String secretKey;

    @Value("${B2_BUCKET_NAME:}")
    private String bucket;

    @PostConstruct
    public void init() {
        var builder = S3Client.builder();
        var presignerBuilder = S3Presigner.builder();

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            var credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
            builder.credentialsProvider(credentialsProvider);
            presignerBuilder.credentialsProvider(credentialsProvider);
        }

        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
            presignerBuilder.region(Region.of(region));
        }

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        s3 = builder.build();
        presigner = presignerBuilder.build();
    }

    @Override
    public String upload(MultipartFile file, String key) {
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();

            s3.putObject(req, RequestBody.fromBytes(file.getBytes()));
            // return the key; URL will be generated with presigned requests for private buckets
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Falha ao enviar arquivo", e);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public String getUrl(String key) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .getObjectRequest(getRequest)
                    .build();

            return presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.error("Falha ao gerar URL pre-assinada para key={}", key, e);
            throw new RuntimeException("Falha ao gerar URL pre-assinada", e);
        }
    }

    @Override
    public StorageService.StorageObject download(String key) {
        try (var resp = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] bytes = resp.readAllBytes();
            String contentType = resp.response().contentType();
            return new StorageService.StorageObject(bytes, contentType == null ? "application/octet-stream" : contentType);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao baixar arquivo", e);
        }
    }
}
