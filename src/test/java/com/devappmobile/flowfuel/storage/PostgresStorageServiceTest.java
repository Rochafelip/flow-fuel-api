package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class PostgresStorageServiceTest {

    @Autowired private StoredFileRepository storedFileRepository;

    private PostgresStorageService service;

    @BeforeEach
    void setUp() {
        service = new PostgresStorageService(storedFileRepository);
    }

    private byte[] pngOf(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void upload_redimensionaImagemGrandeParaNoMaximo512x512() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.png", "image/png", pngOf(2000, 1000));

        service.upload(file, "users/1/photo.png");

        StoredFile saved = storedFileRepository.findById("users/1/photo.png").orElseThrow();
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(saved.getData()));
        assertThat(resized.getWidth()).isLessThanOrEqualTo(512);
        assertThat(resized.getHeight()).isLessThanOrEqualTo(512);
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void upload_comImagemCorrompida_lancaBusinessRule() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.jpg", "image/jpeg", "isso nao e uma imagem".getBytes());

        assertThatThrownBy(() -> service.upload(file, "users/1/photo.jpg"))
                .isInstanceOf(com.devappmobile.flowfuel.exception.BusinessRuleException.class);
    }

    @Test
    void upload_comKeyExistente_sobrescreve() throws IOException {
        MockMultipartFile original = new MockMultipartFile(
                "file", "foto.png", "image/png", pngOf(100, 100));
        MockMultipartFile substituta = new MockMultipartFile(
                "file", "foto2.png", "image/png", pngOf(200, 200));

        service.upload(original, "users/1/photo.png");
        service.upload(substituta, "users/1/photo.png");

        assertThat(storedFileRepository.count()).isEqualTo(1);
    }

    @Test
    void publicUrl_lancaUnsupportedOperation() {
        assertThatThrownBy(() -> service.publicUrl("users/1/photo.png"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void delete_keyExistente_remove() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.png", "image/png", pngOf(100, 100));
        service.upload(file, "users/1/photo.png");

        service.delete("users/1/photo.png");

        assertThat(storedFileRepository.findById("users/1/photo.png")).isEmpty();
    }

    @Test
    void delete_keyInexistente_naoLancaErro() {
        service.delete("nao-existe");
    }
}
