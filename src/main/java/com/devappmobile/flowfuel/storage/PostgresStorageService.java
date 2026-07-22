package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Mantida apenas como historico/rollback ate a remocao definitiva (fora de escopo do design
 * de 2026-07-22 — ver "Fora de escopo"). Nao e mais o bean ativo: R2StorageService assumiu
 * esse papel apos a migracao dos dados existentes.
 */
@Service
@RequiredArgsConstructor
public class PostgresStorageService implements StorageService {

    private static final int MAX_DIMENSION = 512;
    private static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

    private final StoredFileRepository storedFileRepository;

    @Override
    public String upload(MultipartFile file, String key) {
        byte[] resized = resize(file);
        storedFileRepository.save(new StoredFile(key, OUTPUT_CONTENT_TYPE, resized));
        return key;
    }

    @Override
    public void delete(String key) {
        storedFileRepository.deleteById(key);
    }

    @Override
    public String publicUrl(String key) {
        throw new UnsupportedOperationException(
                "PostgresStorageService nao suporta publicUrl; imagens sao servidas via R2StorageService");
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
