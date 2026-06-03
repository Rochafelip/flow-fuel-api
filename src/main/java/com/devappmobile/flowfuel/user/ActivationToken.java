package com.devappmobile.flowfuel.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * Token de ativacao de conta (confirmacao de email).
 *
 * <p>Segue o mesmo padrao dos refresh / password reset tokens (ADR-003): gravamos
 * apenas o SHA-256 do plaintext; o token completo so existe na entrega ao usuario
 * (email / log). E de uso unico ({@code used_at}) e curta duracao.
 */
@Entity(name = "ActivationToken")
@Table(name = "activation_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActivationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public ActivationToken(User user, String tokenHash, LocalDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /** Token ainda valido para efetivar a ativacao da conta. */
    public boolean isUsable() {
        return !isUsed() && !isExpired();
    }
}
