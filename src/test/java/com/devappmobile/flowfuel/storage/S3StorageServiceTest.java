package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    private S3Client s3Client;
    private S3StorageService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        service = new S3StorageService();
        ReflectionTestUtils.setField(service, "s3", s3Client);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "region", "us-west-002");
        ReflectionTestUtils.setField(service, "endpoint", "https://s3.test-endpoint.com");
    }

    @Test
    void upload_putsObjectAndReturnsKey() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", "fake-bytes".getBytes());
        when(s3Client.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = service.upload(file, "users/1/photo.png");

        assertThat(key).isEqualTo("users/1/photo.png");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test
    void delete_deletesObjectByKey() {
        service.delete("users/1/photo.png");

        verify(s3Client).deleteObject(any(java.util.function.Consumer.class));
    }

    @Test
    void download_returnsBytesAndContentType() {
        byte[] content = "image-bytes".getBytes();
        GetObjectResponse response = GetObjectResponse.builder().contentType("image/png").build();
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(response, new ByteArrayInputStream(content));
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        StorageService.StorageObject result = service.download("users/1/photo.png");

        assertThat(result.data()).isEqualTo(content);
        assertThat(result.contentType()).isEqualTo("image/png");
    }
}
