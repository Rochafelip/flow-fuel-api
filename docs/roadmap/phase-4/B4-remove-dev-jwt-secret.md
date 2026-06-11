---
id: B4
phase: 4
priority: low
complexity: low
estimate: 0.25d
status: pending
depends_on: []
---

# B4 — Remover segredo JWT de dev commitado

## Objetivo

Remover o segredo JWT padrão "dev-only" commitado em `application.properties`, forçando configuração explícita mesmo em ambiente de desenvolvimento.

## Problema Atual

`application.properties` (linha ~25):

```properties
jwt.secret=${JWT_SECRET:flowfuel-dev-only-secret-change-in-production-32chars}
```

O `JwtProdValidator` já garante que produção exige `JWT_SECRET` via env var (≥32 chars), mitigando o risco real em produção. Ainda assim, é boa prática não commitar **nenhum** segredo padrão (mesmo "dev-only") em `application.properties` versionado.

## Impacto

- Risco residual baixo (já mitigado pelo `JwtProdValidator` em produção) — por isso classificado como débito técnico, não item crítico.
- Boa prática de higiene de segurança: elimina um segredo "fantasma" versionado no repositório, mesmo que rotulado como "dev-only".

## Arquivos Afetados

- `src/main/resources/application.properties` (linha ~25)
- `src/main/resources/application-dev.properties` (se existir, ou criar)
- `src/main/resources/application-test.properties` / `src/test/resources/application*.properties` (garantir que testes continuam funcionando)
- Documentação de setup local (README do projeto / `.env.example`, se existir)

## Requisitos Técnicos

- Remover o valor default `flowfuel-dev-only-secret-change-in-production-32chars` de `application.properties`.
- Garantir que o ambiente de desenvolvimento local continue funcionando, exigindo `JWT_SECRET` explicitamente via:
  - Variável de ambiente local (documentada em README/`.env.example`), ou
  - Um perfil `dev` com placeholder claramente inválido que force configuração explícita (ex.: `jwt.secret=${JWT_SECRET:CHANGE_ME_SET_JWT_SECRET_ENV_VAR}` combinado com validação adicional, se aplicável).
- **Não quebrar testes automatizados** — `application-test.properties` (ou equivalente) deve continuar fornecendo um valor de `JWT_SECRET` válido para a suíte de testes rodar sem configuração manual.

## Passos de Implementação

1. Localizar todas as ocorrências de `jwt.secret`/`JWT_SECRET` em `src/main/resources/` e `src/test/resources/`.
2. Remover o default `flowfuel-dev-only-secret-change-in-production-32chars` de `application.properties`.
3. Definir a estratégia para ambiente `dev`:
   - Opção A: exigir `JWT_SECRET` via env var local, documentando em README/`.env.example` um valor de exemplo para gerar localmente (ex.: `openssl rand -base64 32`).
   - Opção B: usar um placeholder obviamente inválido (`CHANGE_ME_...`) no profile `dev`, que falha de forma clara (ex.: erro de tamanho mínimo, se já validado em algum lugar) caso não seja sobrescrito.
4. Garantir que `application-test.properties` (ou perfil de teste equivalente) define um `JWT_SECRET` próprio, válido, para que `mvn test` continue rodando sem configuração externa.
5. Atualizar documentação de setup local (README ou equivalente) explicando como configurar `JWT_SECRET` para desenvolvimento.
6. Rodar a suíte completa de testes para confirmar que nada depende do valor default removido.
7. Validar localmente que a aplicação **não sobe** em `dev` sem `JWT_SECRET` configurado (comportamento esperado e documentado).

## Critérios de Aceitação

- `application.properties` não contém mais nenhum valor de segredo JWT hardcoded (nem "dev-only").
- `mvn test` continua passando sem necessidade de configuração externa (perfil de teste fornece seu próprio `JWT_SECRET`).
- Documentação de setup local explica claramente como configurar `JWT_SECRET` para rodar a aplicação em `dev`.
- Comportamento de produção (`JwtProdValidator`) permanece inalterado.

## Estratégia de Testes

- Rodar `mvn test` para confirmar que a suíte de testes não depende do default removido (perfil de teste deve ter seu próprio segredo configurado).
- Validar manualmente: subir a aplicação em perfil `dev` **sem** `JWT_SECRET` configurado → comportamento deve ser claro (falha de startup com mensagem explicativa, ou erro de autenticação óbvio — dependendo da estratégia escolhida no passo 3).
- Validar manualmente: subir a aplicação em perfil `dev` **com** `JWT_SECRET` configurado via env var → funciona normalmente.

## Riscos

- Risco muito baixo — risco real já mitigado pelo `JwtProdValidator` em produção.
- Pequeno risco de fricção para novos desenvolvedores que clonam o repositório e não configuram `JWT_SECRET` imediatamente — mitigado por documentação clara no README/`.env.example`.

## Dependências

Nenhuma.

## Estimativa

0,25 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Atualizar configuração de testes (garantir JWT_SECRET de teste)
- [ ] Atualizar documentação (README/.env.example)
- [ ] Executar testes de regressão
- [ ] Abrir PR
