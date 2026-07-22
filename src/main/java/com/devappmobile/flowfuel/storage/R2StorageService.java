package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class R2StorageService implements StorageService {

    private static final int MAX_DIMENSION = 512;
    private static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private final S3Client s3Client;
    private final String bucket;

    public R2StorageService(S3Client s3Client, @Value("${flowfuel.storage.r2.bucket:}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String upload(MultipartFile file, String key) {
        byte[] resized = resize(file);
        putObject(key, resized, OUTPUT_CONTENT_TYPE);
        return key;
    }

    /** Usado tambem pela migracao one-off (ImagesToR2MigrationRunner) para copiar bytes ja redimensionados sem reprocessar. */
    void putObject(String key, byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public StorageObject download(String key) {
        ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new StorageObject(object.asByteArray(), object.response().contentType());
    }

    private byte[] resize(MultipartFile file) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(file.getInputStream())
                    .size(MAX_DIMENSION, MAX_DIMENSION)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessRuleException("Arquivo de imagem inválido ou corrompido");
        }
    }
}
