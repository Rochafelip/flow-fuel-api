package com.devappmobile.flowfuel.user;

/**
 * Estado de ativacao de uma conta.
 *
 * <p>Uma conta nasce {@link #PENDING_ACTIVATION} no cadastro e so passa a
 * {@link #ACTIVE} apos o usuario confirmar o email (link de ativacao). O login
 * so e permitido para contas {@link #ACTIVE}.
 */
public enum UserStatus {
    PENDING_ACTIVATION,
    ACTIVE
}
