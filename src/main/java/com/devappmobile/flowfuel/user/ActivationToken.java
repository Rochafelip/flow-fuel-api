package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.security.AbstractOpaqueToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * Codigo de ativacao numerico de 6 digitos (nao um token opaco de alta entropia
 * como as demais subclasses de {@link AbstractOpaqueToken}): {@code token_hash} NAO
 * e globalmente unico aqui, pois colisoes entre usuarios diferentes sao esperadas
 * (apenas 1.000.000 de valores possiveis). A unicidade real e por (user_id, token_hash),
 * garantida pelo indice unico composto criado na migration V10.
 */
@Entity(name = "ActivationToken")
@Table(name = "activation_tokens")
@AttributeOverride(name = "tokenHash", column = @Column(name = "token_hash", nullable = false, unique = false, length = 64))
@Getter
@Setter
@NoArgsConstructor
public class ActivationToken extends AbstractOpaqueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public ActivationToken(User user, String tokenHash, LocalDateTime expiresAt) {
        super(tokenHash, expiresAt);
        this.user = user;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isUsable() {
        return !isUsed() && !isExpired();
    }
}
