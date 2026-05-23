package com.devappmobile.flowfuel.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import com.amazonaws.HttpMethod;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import java.util.Date;
import java.time.Instant;
import java.time.Duration;

import java.net.URI;

@Service
public class S3StorageService implements StorageService {

    private S3Client s3;
    private AmazonS3 legacyS3Client;

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

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }

        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        s3 = builder.build();

        // initialize legacy AWS SDK v1 client for presigned URLs
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, secretKey);
            AmazonS3ClientBuilder builderV1 = AmazonS3ClientBuilder.standard()
                    .withCredentials(new AWSStaticCredentialsProvider(creds));

            if (region != null && !region.isBlank()) {
                builderV1.withRegion(region);
            }

            if (endpoint != null && !endpoint.isBlank()) {
                builderV1.withEndpointConfiguration(new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(endpoint, region));
            }

            legacyS3Client = builderV1.build();
        }
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
        // If we have a legacy client, generate a presigned URL (valid 15 minutes)
        if (legacyS3Client != null) {
            try {
                Instant exp = Instant.now().plus(Duration.ofMinutes(15));
                Date expiration = Date.from(exp);
                GeneratePresignedUrlRequest presignedRequest = new GeneratePresignedUrlRequest(bucket, key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
                return legacyS3Client.generatePresignedUrl(presignedRequest).toString();
            } catch (Exception e) {
                // fallthrough to public URL
            }
        }

        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.replaceAll("/$", "") + "/" + bucket + "/" + key;
        }
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucket, region, key);
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
