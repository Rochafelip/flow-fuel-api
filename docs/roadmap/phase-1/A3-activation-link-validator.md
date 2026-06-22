---
id: A3
phase: 1
priority: critical
complexity: low-medium
estimate: 0.5-1d
status: done
depends_on: []
---

# A3 — Eliminar fallback `localhost` do link de ativação em produção

## Objetivo

Eliminar o risco de e-mails de ativação de conta apontarem para `localhost` em produção/staging, adicionando uma validação fail-fast no startup que impede a aplicação de subir com configuração inválida nesses ambientes.

## Problema Atual

`application.properties` (linha ~55) define:

```properties
flowfuel.account-activation.link-base-url=${ACCOUNT_ACTIVATION_LINK_BASE_URL:http://localhost:5173/activate}
```

Esse default `http://localhost:5173/activate` está presente em **todos os perfis**, incluindo `prod`/`staging`, que não sobrescrevem essa chave. Se a env var `ACCOUNT_ACTIVATION_LINK_BASE_URL` não for configurada no deploy de produção, o `SmtpAccountActivationNotifier` envia e-mails reais com link `http://localhost:5173/activate?token=...`.

## Impacto

- **Bug de impacto direto no usuário final:** o fluxo de ativação de conta quebra completamente para usuários reais — o link recebido por e-mail não funciona.
- Potencial exposição de uma URL interna de desenvolvimento em comunicação com usuários finais.
- Onboarding de novos usuários fica bloqueado até alguém perceber o problema (provavelmente via reclamação de usuário, não via alerta automático).

## Arquivos Afetados

- `src/main/resources/application.properties`
- `src/main/resources/application-prod.properties` (se existir; criar/ajustar se necessário)
- `src/main/resources/application-staging.properties` (se existir; criar/ajustar se necessário)
- Novo arquivo: `src/main/java/com/devappmobile/flowfuel/config/ActivationLinkValidator.java`
- Referência de padrão: `src/main/java/com/devappmobile/flowfuel/config/JwtProdValidator.java` (mesmo padrão de fail-fast já em uso)
- Testes:
  - Novo teste para `ActivationLinkValidator` (espelhando `src/test/java/com/devappmobile/flowfuel/config/SentryConfigTest.java` ou padrão equivalente ao de `JwtProdValidator`, se houver teste dedicado)

## Requisitos Técnicos

- Criar `ActivationLinkValidator`, um `@Configuration` com `@Profile({"prod", "staging"})`, que:
  - Injeta `flowfuel.account-activation.link-base-url` via `@Value`.
  - No `@PostConstruct`, lança `IllegalStateException` se o valor contiver `"localhost"` (ou estiver vazio/nulo).
- Seguir o mesmo espírito de robustez do `JwtProdValidator` (fail-fast no startup, mensagem de erro clara apontando a env var correta: `ACCOUNT_ACTIVATION_LINK_BASE_URL`).
- **Ação operacional (fora do código):** validar/confirmar que a env var `ACCOUNT_ACTIVATION_LINK_BASE_URL` está corretamente configurada no ambiente de produção atual **antes do deploy** desta mudança — caso contrário, a aplicação não subirá em prod/staging após o deploy.

## Passos de Implementação

1. Ler `JwtProdValidator` para replicar o padrão de fail-fast (`@Configuration`, `@Profile`, `@PostConstruct`, `@Value`).
2. Criar `ActivationLinkValidator`:
   ```java
   @Configuration
   @Profile({"prod", "staging"})
   public class ActivationLinkValidator {
       @Value("${flowfuel.account-activation.link-base-url}")
       private String linkBaseUrl;

       @PostConstruct
       void validate() {
           if (linkBaseUrl == null || linkBaseUrl.isBlank() || linkBaseUrl.contains("localhost")) {
               throw new IllegalStateException(
                   "ACCOUNT_ACTIVATION_LINK_BASE_URL não pode ser vazio ou apontar para localhost em "
                   + "produção/staging.");
           }
       }
   }
   ```
3. Confirmar que `application.properties` mantém o default `localhost` apenas para os perfis `dev`/`test` (o validador só atua em `prod`/`staging`, então o default pode permanecer como está — a validação é o que impede o uso em produção).
4. **Antes do deploy:** verificar com a equipe/infra (Render) que `ACCOUNT_ACTIVATION_LINK_BASE_URL` está setada corretamente no ambiente de produção atual.
5. Adicionar teste de contexto Spring para o perfil `prod`/`staging` validando que o startup falha quando a propriedade não está configurada (ou aponta para localhost), e sobe normalmente quando configurada corretamente.

## Critérios de Aceitação

- Em perfil `prod` ou `staging`, a aplicação **não sobe** (`IllegalStateException` no startup) se `flowfuel.account-activation.link-base-url` estiver vazio ou contiver `localhost`.
- Em perfil `dev`/`test`, o comportamento atual (default `localhost`) é preservado sem quebrar testes existentes.
- Confirmado (manualmente, fora do código) que a env var `ACCOUNT_ACTIVATION_LINK_BASE_URL` está corretamente configurada no ambiente de produção real antes do merge/deploy desta mudança.

## Estratégia de Testes

- **Teste de contexto Spring (`@SpringBootTest` ou `ApplicationContextRunner`)** com perfil `prod`/`staging`:
  - Caso 1: propriedade ausente/com valor padrão `localhost` → contexto falha ao subir (`IllegalStateException`).
  - Caso 2: propriedade configurada com URL válida (ex.: `https://app.flowfuel.com/activate`) → contexto sobe normalmente.
- Confirmar que perfis `dev`/`test` continuam subindo sem alterações (nenhum teste existente deve quebrar).
- Espelhar a estrutura de teste já usada para `JwtProdValidator`/`SentryConfigTest`, se existirem testes equivalentes.

## Riscos

- **Risco operacional real:** se a env var não estiver configurada em produção hoje, o deploy desta mudança fará a aplicação **não subir** — por isso a validação/confirmação operacional é um passo obrigatório antes do deploy, não apenas uma recomendação.
- Baixo risco de código — segue padrão já validado (`JwtProdValidator`).

## Dependências

Nenhuma dependência de código. Requer **ação operacional prévia**: confirmar configuração de `ACCOUNT_ACTIVATION_LINK_BASE_URL` no ambiente de produção atual antes do deploy.

## Estimativa

0,5–1 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Confirmar configuração de `ACCOUNT_ACTIVATION_LINK_BASE_URL` em produção (ação operacional)
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
