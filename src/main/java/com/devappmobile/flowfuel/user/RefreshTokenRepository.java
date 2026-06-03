package com.devappmobile.flowfuel.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(Long userId);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now "
            + "WHERE rt.user.id = :userId AND rt.revokedAt IS NULL")
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Limpa a referencia replaced_by nas linhas que serao deletadas em seguida.
     * Necessario porque o FK self-referencing nao permite ON DELETE SET NULL
     * via @OnDelete do Hibernate, e o DELETE em massa violaria o constraint.
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.replacedBy = NULL "
            + "WHERE rt.replacedBy IS NOT NULL "
            + "AND ((rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoff) OR rt.expiresAt < :cutoff)")
    int clearReplacedByOnOldTokens(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM RefreshToken rt "
            + "WHERE (rt.revokedAt IS NOT NULL AND rt.revokedAt < :cutoff) OR rt.expiresAt < :cutoff")
    int deleteOldTokens(@Param("cutoff") LocalDateTime cutoff);
}
