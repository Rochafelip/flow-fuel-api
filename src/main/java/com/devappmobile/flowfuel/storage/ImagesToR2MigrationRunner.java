package com.devappmobile.flowfuel.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Migracao one-off dos arquivos existentes em stored_files (Postgres) para o R2, preservando
 * a mesma key. So roda quando flowfuel.storage.migration.enabled=true (ligar manualmente
 * contra prod via fly secrets, rodar uma vez, desligar). Ver
 * docs/superpowers/specs/2026-07-22-r2-image-storage-design.md
 */
@Component
@ConditionalOnProperty(name = "flowfuel.storage.migration.enabled", havingValue = "true")
public class ImagesToR2MigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ImagesToR2MigrationRunner.class);

    private final StoredFileRepository storedFileRepository;
    private final R2StorageService r2StorageService;

    public ImagesToR2MigrationRunner(StoredFileRepository storedFileRepository, R2StorageService r2StorageService) {
        this.storedFileRepository = storedFileRepository;
        this.r2StorageService = r2StorageService;
    }

    @Override
    public void run(String... args) {
        MigrationSummary summary = migrate();
        log.info("[ImagesToR2MigrationRunner] total={} sucesso={} falha={}",
                summary.total(), summary.success(), summary.failed());
    }

    MigrationSummary migrate() {
        List<StoredFile> files = storedFileRepository.findAll();
        int success = 0;
        int failed = 0;
        for (StoredFile file : files) {
            try {
                r2StorageService.putObject(file.getKey(), file.getData(), file.getContentType());
                success++;
            } catch (Exception e) {
                failed++;
                log.error("[ImagesToR2MigrationRunner] falha ao migrar key={}", file.getKey(), e);
            }
        }
        return new MigrationSummary(files.size(), success, failed);
    }

    record MigrationSummary(int total, int success, int failed) {}
}
