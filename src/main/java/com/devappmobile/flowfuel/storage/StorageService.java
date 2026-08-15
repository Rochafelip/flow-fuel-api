package com.devappmobile.flowfuel.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    String upload(MultipartFile file, String key);

    void delete(String key);

    byte[] download(String key);
}
