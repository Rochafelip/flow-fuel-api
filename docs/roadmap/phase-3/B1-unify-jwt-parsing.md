---
id: B1
phase: 3
priority: medium
complexity: low
estimate: 0.5d
status: pending
depends_on: []
---

# B1 — Unificar parse do JWT (`tryParse` único)

## Objetivo

Eliminar o duplo parse do JWT por requisição autenticada, expondo um único método `Optional<Claims> tryParse(String token)` em `JwtUtil` e derivando `email`/`userId`/validade a partir do mesmo objeto `Claims`.

## Problema Atual

`JwtAuthenticationFilter.doFilterInternal` (linhas ~73–87) chama `jwtUtil.extractEmail(token)` e, em seguida, `jwtUtil.validateToken(token)` — cada um internamente chama `parseClaims(token)`. O token é assinado/parseado **duas vezes** por requisição autenticada.

## Impacto

- Baixo custo unitário (operação HMAC), mas é trabalho redundante em **todo** request autenticado da API — overhead desnecessário em escala.
- API do `JwtUtil` menos limpa/coesa (dois pontos de entrada que internamente fazem a mesma operação).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/config/JwtUtil.java`
- `src/main/java/com/devappmobile/flowfuel/config/JwtAuthenticationFilter.java`
- Testes:
  - `src/test/java/com/devappmobile/flowfuel/config/JwtUtilTest.java`

## Requisitos Técnicos

- Adicionar a `JwtUtil` um método único:
  ```java
  public Optional<Claims> tryParse(String token) {
      try {
          return Optional.of(parseClaims(token));
      } catch (JwtException | IllegalArgumentException e) {
          return Optional.empty();
      }
  }
  ```
- Atualizar `JwtAuthenticationFilter.doFilterInternal` para chamar `tryParse(token)` **uma única vez** e derivar `email`/`userId`/validade (`isExpired`, etc.) do mesmo `Claims` retornado.
- Avaliar se `extractEmail`/`validateToken` (métodos públicos existentes) devem ser mantidos para compatibilidade com outros chamadores (`grep` para confirmar usos) ou podem ser removidos/refatorados para usar `tryParse` internamente.
- Preservar exatamente o mesmo comportamento de erro (token inválido/expirado → `Optional.empty()` → fluxo de autenticação falha da mesma forma que hoje).

## Passos de Implementação

1. Ler `JwtUtil` para entender `parseClaims`, `extractEmail`, `validateToken` e como `parseClaims` trata exceptions hoje.
2. Implementar `tryParse(String token): Optional<Claims>` encapsulando o parse + tratamento de exceptions.
3. Buscar (`grep`) todos os chamadores de `extractEmail`/`validateToken` no código de produção (não apenas `JwtAuthenticationFilter`).
4. Atualizar `JwtAuthenticationFilter.doFilterInternal` para chamar `tryParse` uma vez e extrair email/userId/expiração do `Claims` resultante.
5. Se `extractEmail`/`validateToken` não tiverem outros chamadores além do filtro, refatorá-los para serem implementados em termos de `tryParse` (ou removê-los, se não usados em mais nenhum lugar incluindo testes).
6. Rodar `JwtUtilTest` e testes de integração que dependem de autenticação JWT (praticamente todos os `*ControllerIntegrationTest`).

## Critérios de Aceitação

- `JwtUtil` expõe `tryParse(String token): Optional<Claims>`.
- `JwtAuthenticationFilter` chama o parser do JWT **uma única vez** por requisição autenticada.
- Comportamento de autenticação (token válido → autentica; token inválido/expirado → `401`/não autenticado) permanece idêntico.
- `JwtUtilTest` cobre `tryParse` para token válido, inválido e expirado.

## Estratégia de Testes

- **Unit tests (`JwtUtilTest`):**
  - `tryParse` com token válido → `Optional` presente com `Claims` corretos (email, userId, exp).
  - `tryParse` com token expirado → `Optional.empty()`.
  - `tryParse` com token malformado/assinatura inválida → `Optional.empty()`.
- **Regressão:** rodar todos os `*ControllerIntegrationTest` que dependem de autenticação (`UserControllerIntegrationTest`, `VehicleControllerIntegrationTest`, `RefuelControllerIntegrationTest`, `VehicleEventControllerIntegrationTest`, `DashboardControllerIntegrationTest`) — devem passar sem alteração.
- (Opcional) Teste de performance/contagem: verificar (via spy/mock em teste unitário do filtro) que `parseClaims`/biblioteca JWT é invocada apenas uma vez por requisição.

## Riscos

- Muito baixo risco — refatoração interna isolada ao parsing de JWT, sem mudança de contrato externo.
- Atenção a comportamento de exceptions: garantir que `tryParse` capture exatamente as mesmas exceptions que `parseClaims`/`validateToken` tratam hoje (ex.: `ExpiredJwtException`, `MalformedJwtException`, `SignatureException`), para não alterar a granularidade de erro.

## Dependências

Nenhuma. Agrupado na Fase 3 por afinidade com os demais itens de segurança ([[M1-opaque-token-generator]]), mas pode ser feito a qualquer momento e em paralelo com qualquer outra task.

## Estimativa

0,5 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
