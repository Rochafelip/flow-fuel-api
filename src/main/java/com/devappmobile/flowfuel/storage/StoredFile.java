package com.devappmobile.flowfuel.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "stored_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StoredFile {

    @Id
    @Column(name = "`key`")
    private String key;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(nullable = false, length = 100_000_000)
    private byte[] data;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public StoredFile(String key, String contentType, byte[] data) {
        this.key = key;
        this.contentType = contentType;
        this.data = data;
    }
}
