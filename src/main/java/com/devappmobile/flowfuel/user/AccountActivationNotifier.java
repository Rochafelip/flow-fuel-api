package com.devappmobile.flowfuel.user;

/**
 * Canal de entrega do link de ativacao de conta ao usuario.
 *
 * <p>Ponto de extensao deliberado (mesmo padrao de {@link PasswordResetNotifier}):
 * o stub {@link LoggingAccountActivationNotifier} apenas registra o token em log,
 * e {@link SmtpAccountActivationNotifier} envia o email real via JavaMailSender.
 * O bean ativo e escolhido por configuracao — nada no fluxo precisa mudar.
 */
public interface AccountActivationNotifier {

    /**
     * Entrega o token de ativacao (plaintext) ao usuario, normalmente embutido
     * num link de ativacao.
     *
     * @param user             dono da conta
     * @param activationToken  token em texto puro — nunca persistido, apenas entregue
     */
    void sendActivationLink(User user, String activationToken);
}
