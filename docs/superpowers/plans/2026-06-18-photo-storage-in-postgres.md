# Foto de Perfil no Postgres Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o armazenamento de foto de perfil (Backblaze B2/S3, nunca configurado em produção) por armazenamento direto no Postgres (Neon), eliminando uma dependência externa para um deploy de 1-2 usuários.

**Architecture:** Nova entidade JPA `StoredFile` (tabela `stored_files`: key/content_type/data bytea/created_at) + `StoredFileRepository extends JpaRepository<StoredFile, String>`. Nova implementação `PostgresStorageService implements StorageService` usa esse repositório, redimensionando a imagem para 512x512 via Thumbnailator antes de salvar. `S3StorageService` e a dependência AWS SDK S3 são removidos. `StorageService.getUrl()` é removido da interface — o DTO de resposta perde o campo `profilePictureUrl`/`signedUrl`.

**Tech Stack:** Spring Data JPA (Postgres em prod, H2 em testes — já o padrão do projeto), Flyway, Thumbnailator (nova dependência Maven).

---

## Visão geral dos arquivos

- **Criar:** `src/main/resources/db/migration/V8__create_stored_files.sql`
- **Criar:** `src/main/java/com/devappmobile/flowfuel/storage/StoredFile.java` (entidade JPA)
- **Criar:** `src/main/java/com/devappmobile/flowfuel/storage/StoredFileRepository.java`
- **Criar:** `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`
- **Criar:** `src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java`
- **Modificar:** `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java` (remover `getUrl`)
- **Modificar:** `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java` (remover uso de `getUrl`)
- **Modificar:** `src/main/java/com/devappmobile/flowfuel/user/UserResponseDTO.java` (remover `profilePictureUrl`)
- **Modificar:** `src/main/java/com/devappmobile/flowfuel/user/UploadResponse.java` (remover `signedUrl`)
- **Modificar:** `src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java` (ajustar asserts)
- **Modificar:** `pom.xml` (remover AWS SDK S3, adicionar Thumbnailator)
- **Modificar:** `render.yaml`, `.env.example`, `.env.prod.example` (remover envs `B2_S3_*`)
- **Modificar:** `docs/deploy.md` (remover pendência de storage B2)
- **Deletar:** `src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java`
- **Deletar:** `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`

---

### Task 1: Migration da tabela `stored_files`

**Files:**
- Create: `src/main/resources/db/migration/V8__create_stored_files.sql`

- [ ] **Step 1: Criar o arquivo de migration**

```sql
CREATE TABLE stored_files (
    key VARCHAR(255) PRIMARY KEY,
    content_type VARCHAR(100) NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Validar que o Flyway aplica a migration sem erro**

Run: `./mvnw -B clean verify`
Expected: `BUILD SUCCESS`. Os testes de integração que sobem o contexto Spring (ex: `RefuelRepositoryAggregateTest`) já rodam Flyway contra o H2 de teste — se a sintaxe SQL não for compatível com H2, o build falha aqui.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V8__create_stored_files.sql
git commit -m "feat(storage): add stored_files migration"
```

---

### Task 2: Entidade `StoredFile` e repositório

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/storage/StoredFile.java`
- Create: `src/main/java/com/devappmobile/flowfuel/storage/StoredFileRepository.java`

- [ ] **Step 1: Criar a entidade**

```java
package com.devappmobile.flowfuel.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stored_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StoredFile {

    @Id
    private String key;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Lob
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public StoredFile(String key, String contentType, byte[] data) {
        this.key = key;
        this.contentType = contentType;
        this.data = data;
        this.createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Criar o repositório**

```java
package com.devappmobile.flowfuel.storage;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
}
```

- [ ] **Step 3: Compilar para validar**

Run: `./mvnw -B compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/StoredFile.java src/main/java/com/devappmobile/flowfuel/storage/StoredFileRepository.java
git commit -m "feat(storage): add StoredFile entity and repository"
```

---

### Task 3: Adicionar dependência Thumbnailator

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Remover a dependência do AWS SDK S3**

Em `pom.xml`, localizar e remover o bloco (atualmente perto da linha 127):

```xml
		<!-- AWS S3 SDK (used with Backblaze S3-compatible endpoint) -->
		<dependency>
			<groupId>software.amazon.awssdk</groupId>
			<artifactId>s3</artifactId>
			<version>2.20.110</version>
		</dependency>
```

- [ ] **Step 2: Adicionar a dependência do Thumbnailator no mesmo lugar**

```xml
		<!-- Redimensionamento de imagens (foto de perfil), armazenadas no Postgres -->
		<dependency>
			<groupId>net.coobird</groupId>
			<artifactId>thumbnailator</artifactId>
			<version>0.4.20</version>
		</dependency>
```

- [ ] **Step 3: Validar que o projeto ainda compila (S3StorageService ainda existe nesse ponto, então vai falhar — é esperado)**

Run: `./mvnw -B compile 2>&1 | tail -30`
Expected: FAIL com erros de "package software.amazon.awssdk... does not exist" em `S3StorageService.java`. Isso é esperado — esses arquivos são removidos na Task 5. Não commitar ainda.

---

### Task 4: `PostgresStorageService`

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java`

- [ ] **Step 1: Escrever o teste (falhando, pois a classe ainda não existe)**

```java
package com.devappmobile.flowfuel.storage;

import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
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
    void download_keyExistente_retornaDadosEContentType() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "foto.png", "image/png", pngOf(100, 100));
        service.upload(file, "users/1/photo.png");

        StorageService.StorageObject result = service.download("users/1/photo.png");

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.data()).isNotEmpty();
    }

    @Test
    void download_keyInexistente_lancaResourceNotFound() {
        assertThatThrownBy(() -> service.download("nao-existe"))
                .isInstanceOf(ResourceNotFoundException.class);
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
```

- [ ] **Step 2: Rodar o teste para confirmar que falha (classe não existe)**

Run: `./mvnw -B test -Dtest=PostgresStorageServiceTest`
Expected: FAIL com "cannot find symbol: class PostgresStorageService"

- [ ] **Step 3: Implementar `PostgresStorageService`**

```java
package com.devappmobile.flowfuel.storage;

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
            throw new com.devappmobile.flowfuel.exception.BusinessRuleException(
                    "Arquivo de imagem inválido ou corrompido");
        }
    }
}
```

- [ ] **Step 4: Rodar o teste para confirmar que passa**

Run: `./mvnw -B test -Dtest=PostgresStorageServiceTest`
Expected: PASS (5 testes)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/PostgresStorageService.java src/test/java/com/devappmobile/flowfuel/storage/PostgresStorageServiceTest.java
git commit -m "feat(storage): add PostgresStorageService with image resize"
```

---

### Task 5: Remover `S3StorageService` e ajustar a interface `StorageService`

**Files:**
- Delete: `src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java`
- Delete: `src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java`

- [ ] **Step 1: Remover os dois arquivos do S3**

```bash
git rm src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java
git rm src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java
```

- [ ] **Step 2: Remover `getUrl` da interface**

Editar `src/main/java/com/devappmobile/flowfuel/storage/StorageService.java` para o conteúdo final:

```java
package com.devappmobile.flowfuel.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    String upload(MultipartFile file, String key);

    void delete(String key);

    record StorageObject(byte[] data, String contentType) {}

    StorageObject download(String key);
}
```

- [ ] **Step 3: Compilar (vai falhar — `UserProfileService` ainda chama `getUrl`, corrigido na Task 6)**

Run: `./mvnw -B compile 2>&1 | tail -20`
Expected: FAIL com "cannot find symbol: method getUrl" em `UserProfileService.java`. Esperado, não commitar ainda.

---

### Task 6: Atualizar `UserProfileService`, DTOs e `UserController`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UserResponseDTO.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/user/UploadResponse.java`

- [ ] **Step 1: Remover `profilePictureUrl` de `UserResponseDTO`**

Editar `src/main/java/com/devappmobile/flowfuel/user/UserResponseDTO.java` para:

```java
package com.devappmobile.flowfuel.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {

    private Long id;
    private String email;
    private String name;
    private String phone;
    private String profilePicture;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserResponseDTO from(User user) {
        return new UserResponseDTO(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getProfilePicture(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
```

- [ ] **Step 2: Remover `signedUrl` de `UploadResponse`**

Editar `src/main/java/com/devappmobile/flowfuel/user/UploadResponse.java` para:

```java
package com.devappmobile.flowfuel.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String internalUrl; // /auth/{userId}/profile-picture
}
```

- [ ] **Step 3: Atualizar `UserProfileService` removendo as chamadas a `getUrl`**

Em `src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java`, substituir o método `getUserProfile` (linhas 24-40) por:

```java
    public UserResponseDTO getUserProfile(Long userId) {
        User user = findUserOrThrow(userId);
        String profileKey = user.getProfilePicture();
        String internalUrl = profileKey != null ? ("/auth/" + userId + "/profile-picture") : null;

        UserResponseDTO dto = UserResponseDTO.from(user);
        dto.setProfilePicture(internalUrl);
        return dto;
    }
```

E substituir o final de `uploadProfilePictureResponse` (a partir da linha onde monta `internalUrl`, originalmente linhas 107-114) por:

```java
        String internalUrl = "/auth/" + userId + "/profile-picture";
        return new UploadResponse(internalUrl);
```

- [ ] **Step 4: Compilar**

Run: `./mvnw -B compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/storage/S3StorageService.java src/test/java/com/devappmobile/flowfuel/storage/S3StorageServiceTest.java src/main/java/com/devappmobile/flowfuel/storage/StorageService.java src/main/java/com/devappmobile/flowfuel/user/UserProfileService.java src/main/java/com/devappmobile/flowfuel/user/UserResponseDTO.java src/main/java/com/devappmobile/flowfuel/user/UploadResponse.java
git commit -m "refactor(storage): remove S3 backend and signed URL concept"
```

---

### Task 7: Ajustar `UserProfileServiceTest`

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java`

- [ ] **Step 1: Atualizar o teste `getUserProfile_retornaProfilePictureUrl`**

Substituir (remover a referência a `storageService.getUrl` e a asserção de `profilePictureUrl`):

```java
    @Test
    void getUserProfile_retornaProfilePicturePath() {
        existingUser.setProfilePicture("profile_pictures/1_foto.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));

        UserResponseDTO response = userProfileService.getUserProfile(1L);

        assertThat(response.getProfilePicture()).isEqualTo("/auth/1/profile-picture");
    }
```

- [ ] **Step 2: Atualizar o teste `uploadProfilePictureResponse_comImagemValida_retornaUrls`**

Substituir por (sem `storageService.getUrl` e sem assert de `getSignedUrl`):

```java
    @Test
    void uploadProfilePictureResponse_comImagemValida_retornaInternalUrl() {
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[100]);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenReturn(existingUser);

        UploadResponse response = userProfileService.uploadProfilePictureResponse(1L, file);

        assertThat(response).isNotNull();
        assertThat(response.getInternalUrl()).isEqualTo("/auth/1/profile-picture");
        assertThat(existingUser.getProfilePicture()).isEqualTo("profile_pictures/1_foto.jpg");
    }
```

- [ ] **Step 3: Rodar a suíte completa de testes do pacote `user`**

Run: `./mvnw -B test -Dtest=UserProfileServiceTest`
Expected: PASS (todos os testes da classe)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/user/UserProfileServiceTest.java
git commit -m "test(storage): update UserProfileServiceTest for removed signed URL"
```

---

### Task 8: Limpar configuração do B2 (render.yaml, .env.example, .env.prod.example, docs/deploy.md)

**Files:**
- Modify: `render.yaml`
- Modify: `.env.example`
- Modify: `.env.prod.example`
- Modify: `docs/deploy.md`

- [ ] **Step 1: Remover o bloco B2 do `render.yaml`**

Remover (linhas 69-79 atualmente):

```yaml
      # Storage Backblaze B2 (S3-compativel)
      - key: B2_S3_ENDPOINT
        sync: false
      - key: B2_S3_REGION
        sync: false
      - key: B2_S3_ACCESS_KEY
        sync: false
      - key: B2_S3_SECRET
        sync: false
      - key: B2_BUCKET_NAME
        sync: false
```

- [ ] **Step 2: Remover o bloco B2 do `.env.example`**

Remover:

```
B2_S3_ENDPOINT=https://s3.<region>.backblazeb2.com
B2_S3_REGION=<region>
B2_S3_ACCESS_KEY=<sua-application-key-id>
B2_S3_SECRET=<sua-application-key>
B2_BUCKET_NAME=<nome-do-bucket>
```

- [ ] **Step 3: Remover o bloco B2 do `.env.prod.example`**

Remover:

```
# Storage Backblaze B2 (opcional). Sem isso, upload de foto de perfil falha,
# mas o resto da API funciona normalmente.
B2_S3_ENDPOINT=
B2_S3_REGION=us-west-002
B2_S3_ACCESS_KEY=
B2_S3_SECRET=
B2_BUCKET_NAME=
```

- [ ] **Step 4: Atualizar `docs/deploy.md`**

Na seção "Pontos de atenção / pendências" de `docs/deploy.md`, remover o item:

```
- **Storage B2 (foto de perfil) não configurado** — variáveis `B2_S3_*` não foram setadas; upload de foto falhará até serem configuradas.
```

E adicionar, na mesma seção, uma linha confirmando a resolução:

```
- **Upload de foto de perfil** agora usa o próprio Postgres (tabela `stored_files`, ver `docs/superpowers/specs/2026-06-18-photo-storage-in-postgres-design.md`) — sem dependência externa de storage.
```

- [ ] **Step 5: Commit**

```bash
git add render.yaml .env.example .env.prod.example
git commit -m "chore(config): remove B2/S3 env vars, no longer used"
```

(Nota: `docs/` está no `.gitignore` do projeto — a alteração em `docs/deploy.md` fica só local, sem commit, conforme decisão já tomada nesta conversa.)

---

### Task 9: Suíte completa e deploy

**Files:** nenhum (validação e deploy)

- [ ] **Step 1: Rodar a suíte completa**

Run: `./mvnw -B clean verify`
Expected: `BUILD SUCCESS`, sem falhas

- [ ] **Step 2: Buildar a imagem Docker localmente para garantir que builda sem a dependência S3**

Run: `docker build -t flowfuel:photo-storage-test .`
Expected: build completa sem erro

- [ ] **Step 3: Deploy no Fly.io**

Run:
```bash
export FLYCTL_INSTALL="$HOME/.fly"
export PATH="$FLYCTL_INSTALL/bin:$PATH"
flyctl deploy
```
Expected: `Machine ... update succeeded` e health check `1/1`

- [ ] **Step 4: Testar o fluxo manualmente via curl**

Fazer login para obter token, depois:
```bash
curl -X POST https://flowfuel-api.fly.dev/auth/<userId>/upload-profile-picture \
  -H "Authorization: Bearer <token>" \
  -F "file=@/caminho/para/foto.jpg"

curl https://flowfuel-api.fly.dev/auth/<userId>/profile-picture \
  -H "Authorization: Bearer <token>" \
  -o /tmp/foto-baixada.jpg
```
Expected: upload retorna `{"internalUrl":"/auth/<userId>/profile-picture"}`; download salva um JPEG válido em `/tmp/foto-baixada.jpg` com no máximo 512x512.
