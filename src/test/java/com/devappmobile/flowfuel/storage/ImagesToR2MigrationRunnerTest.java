package com.devappmobile.flowfuel.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImagesToR2MigrationRunnerTest {

    @Mock private StoredFileRepository storedFileRepository;
    @Mock private R2StorageService r2StorageService;

    @InjectMocks private ImagesToR2MigrationRunner runner;

    @Test
    void migrate_copiaTodasAsLinhasParaR2() {
        StoredFile a = new StoredFile("k1", "image/jpeg", new byte[]{1});
        StoredFile b = new StoredFile("k2", "image/jpeg", new byte[]{2});
        when(storedFileRepository.findAll()).thenReturn(List.of(a, b));

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        verify(r2StorageService).putObject("k1", a.getData(), "image/jpeg");
        verify(r2StorageService).putObject("k2", b.getData(), "image/jpeg");
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.success()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(0);
    }

    @Test
    void migrate_linhaComFalha_naoAbortaAsDemaisEContabilizaFalha() {
        StoredFile a = new StoredFile("k1", "image/jpeg", new byte[]{1});
        StoredFile b = new StoredFile("k2", "image/jpeg", new byte[]{2});
        when(storedFileRepository.findAll()).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("falha de rede"))
                .when(r2StorageService).putObject(eq("k1"), any(), any());

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        verify(r2StorageService).putObject("k2", b.getData(), "image/jpeg");
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    void migrate_semLinhas_retornaSummaryZerado() {
        when(storedFileRepository.findAll()).thenReturn(List.of());

        ImagesToR2MigrationRunner.MigrationSummary summary = runner.migrate();

        assertThat(summary.total()).isZero();
        assertThat(summary.success()).isZero();
        assertThat(summary.failed()).isZero();
        verifyNoInteractions(r2StorageService);
    }
}
