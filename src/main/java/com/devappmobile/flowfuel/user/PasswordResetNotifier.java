package com.devappmobile.flowfuel.user;

/**
 * Canal de entrega do token de redefinicao de senha ao usuario.
 *
 * <p>Ponto de extensao deliberado (mesmo padrao de {@link AccountActivationNotifier}):
 * o stub {@link LoggingPasswordResetNotifier} apenas registra o token em log,
 * e {@link SmtpPasswordResetNotifier} envia o email real via JavaMailSender.
 * O bean ativo e escolhido por configuracao — nada no fluxo precisa mudar.
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
