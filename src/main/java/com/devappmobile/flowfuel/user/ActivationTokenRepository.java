package com.devappmobile.flowfuel.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ActivationTokenRepository extends JpaRepository<ActivationToken, Long> {

    /**
     * Busca escopada por usuario: token_hash NAO e globalmente unico para
     * codigos de 6 digitos (colisoes entre usuarios sao esperadas), entao a
     * busca precisa do par (user_id, token_hash) para ser inequivoca.
     */
    Optional<ActivationToken> findByUserIdAndTokenHash(Long userId, String tokenHash);

    /**
     * Invalida (marca como usados) todos os tokens ativos de um usuario.
     * Chamado ao emitir um novo token: cada solicitacao invalida as anteriores,
     * garantindo que apenas o ultimo token enviado seja valido.
     */
    @Modifying
    @Query("UPDATE ActivationToken t SET t.usedAt = :now "
            + "WHERE t.user.id = :userId AND t.usedAt IS NULL AND t.expiresAt > :now")
    int invalidateActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM ActivationToken t "
            + "WHERE t.expiresAt < :cutoff OR (t.usedAt IS NOT NULL AND t.usedAt < :cutoff)")
    int deleteOldTokens(@Param("cutoff") LocalDateTime cutoff);
}
