package com.devappmobile.flowfuel.user;

import com.devappmobile.flowfuel.common.security.AbstractOpaqueToken;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

@Entity(name = "ActivationToken")
@Table(name = "activation_tokens")
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
