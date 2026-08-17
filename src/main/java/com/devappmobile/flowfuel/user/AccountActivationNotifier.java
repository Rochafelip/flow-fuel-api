package com.devappmobile.flowfuel.user;

/**
 * Canal de entrega do codigo de ativacao de conta ao usuario.
 *
 * <p>Ponto de extensao deliberado (mesmo padrao de {@link PasswordResetNotifier}):
 * o stub {@link LoggingAccountActivationNotifier} apenas registra o codigo em log,
 * e {@link SmtpAccountActivationNotifier} envia o email real via JavaMailSender.
 * O bean ativo e escolhido por configuracao — nada no fluxo precisa mudar.
 */
public interface AccountActivationNotifier {

    /**
     * Entrega o codigo de ativacao (plaintext) ao usuario, para digitacao manual
     * na tela de ativacao do app.
     *
     * @param user             dono da conta
     * @param activationToken  codigo numerico de 6 digitos, em texto puro — nunca persistido, apenas entregue
     */
    void sendActivationCode(User user, String activationToken);
}
