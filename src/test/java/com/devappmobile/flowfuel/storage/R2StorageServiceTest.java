package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class R2StorageServiceTest {

    @Mock private S3Client s3Client;

    private R2StorageService service;

    @BeforeEach
    void setUp() {
        service = new R2StorageService(s3Client, "test-bucket", "https://pub-test.r2.dev");
    }

    private byte[] pngOf(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void upload_redimensionaImagemGrandeEEnviaParaR2() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "foto.png", "image/png", pngOf(2000, 1000));

        service.upload(file, "users/1/photo.png");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("users/1/photo.png");
        assertThat(requestCaptor.getValue().contentType()).isEqualTo("image/jpeg");

        BufferedImage resized = ImageIO.read(bodyCaptor.getValue().contentStreamProvider().newStream());
        assertThat(resized.getWidth()).isLessThanOrEqualTo(512);
        assertThat(resized.getHeight()).isLessThanOrEqualTo(512);
    }

    @Test
    void upload_comImagemCorrompida_lancaBusinessRuleENaoChamaS3() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.jpg", "image/jpeg", "isso nao e uma imagem".getBytes());

        assertThatThrownBy(() -> service.upload(file, "users/1/photo.jpg"))
                .isInstanceOf(com.devappmobile.flowfuel.exception.BusinessRuleException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void delete_chamaDeleteObjectComBucketEKey() {
        service.delete("users/1/photo.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo("users/1/photo.png");
    }

    @Test
    void publicUrl_concatenaBaseUrlEKey() {
        assertThat(service.publicUrl("users/1/photo.png"))
                .isEqualTo("https://pub-test.r2.dev/users/1/photo.png");
    }
}
