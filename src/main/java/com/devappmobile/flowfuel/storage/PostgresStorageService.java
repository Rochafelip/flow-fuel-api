package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.BusinessRuleException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
    public StorageObject download(String key) {
        StoredFile storedFile = storedFileRepository.findById(key)
                .orElseThrow(() -> new ResourceNotFoundException("Arquivo", key));
        return new StorageObject(storedFile.getData(), storedFile.getContentType());
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
