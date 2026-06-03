package com.devappmobile.flowfuel.user;

/**
 * Canal de entrega do token de redefinicao de senha ao usuario.
 *
 * <p>Ponto de extensao deliberado: hoje a unica implementacao
 * ({@link LoggingPasswordResetNotifier}) apenas registra o token em log. Quando
 * a infraestrutura de email for adicionada (spring-boot-starter-mail +
 * JavaMailSender), basta fornecer um novo bean que implemente esta interface —
 * nada mais no fluxo precisa mudar.
 */
public interface PasswordResetNotifier {

    /**
     * Entrega o token de redefinicao (plaintext) ao usuario.
     *
     * @param user        dono da conta
     * @param resetToken  token em texto puro — nunca persistido, apenas entregue
     */
    void sendResetToken(User user, String resetToken);
}
