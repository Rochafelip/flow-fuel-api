---
id: B3
phase: 4
priority: low
complexity: low
estimate: 0.25d
status: pending
depends_on: []
---

# B3 — Corrigir NPE em `User.addVehicle`/`removeVehicle`

## Objetivo

Inicializar o campo `vehicles` em `User.java` para evitar `NullPointerException` ao chamar `addVehicle`/`removeVehicle` em uma entidade `User` recém-instanciada (`new User()`).

## Problema Atual

`User.java` (linhas ~71–79):

```java
private List<Vehicle> vehicles;   // nunca inicializado

public void addVehicle(Vehicle vehicle) {
    vehicles.add(vehicle);        // NPE se `vehicles == null`
    vehicle.setUser(this);
}
```

O campo `vehicles` nunca é inicializado com `new ArrayList<>()`. Atualmente **não é usado pelos services** (que setam `vehicle.setUser(user)` diretamente), mas é um método público de domínio que qualquer novo código pode passar a chamar.

## Impacto

- "Mina terrestre" para código futuro: qualquer novo código que instancie `new User()` e chame `addVehicle`/`removeVehicle` resulta em `NullPointerException`.
- Bug latente, sem impacto atual em produção (métodos não usados pelos services hoje), mas com risco crescente conforme o código evolui (ex.: durante o split de `UserService` em [[M2-split-user-service]]).

## Arquivos Afetados

- `src/main/java/com/devappmobile/flowfuel/user/User.java` (linhas ~71–79)
- Testes:
  - Novo teste unitário para `User.addVehicle`/`removeVehicle` (ex.: em um futuro `UserTest.java`, ou adicionar caso em teste existente que cubra a entidade `User`)

## Requisitos Técnicos

- Inicializar o campo:
  ```java
  private List<Vehicle> vehicles = new ArrayList<>();
  ```
- Confirmar que essa inicialização não conflita com o mapeamento JPA (`@OneToMany(mappedBy = "user", ...)`) — inicialização de coleção com `new ArrayList<>()` é prática padrão e segura com Hibernate.
- Não é necessário (nem recomendado, dado o baixo risco e baixo esforço) remover os métodos `addVehicle`/`removeVehicle` — apenas corrigir a inicialização, já que são parte da API de domínio da entidade.

## Passos de Implementação

1. Localizar a declaração do campo `vehicles` em `User.java`.
2. Alterar para `private List<Vehicle> vehicles = new ArrayList<>();`.
3. Adicionar um teste unitário simples instanciando `new User()` e chamando `addVehicle(vehicle)`/`removeVehicle(vehicle)`, verificando que não lança exception e que a coleção reflete a operação.
4. Rodar testes existentes de `User`/`UserService` para garantir que a inicialização não interfere no mapeamento JPA (ex.: `@DataJpaTest` ou testes de integração que persistem `User`).

## Critérios de Aceitação

- `new User().addVehicle(vehicle)` e `new User().removeVehicle(vehicle)` não lançam `NullPointerException`.
- Mapeamento JPA de `User.vehicles` continua funcionando normalmente (testes de persistência existentes passam).

## Estratégia de Testes

- **Unit test simples:** `new User()` → `addVehicle(vehicle)` → verificar que `user.getVehicles()` contém o veículo e `vehicle.getUser() == user`. Repetir para `removeVehicle`.
- Rodar testes de integração existentes que persistem/recuperam `User` com `vehicles` (ex.: `UserControllerIntegrationTest`, `VehicleControllerIntegrationTest`) para garantir que a inicialização eager da coleção não introduz efeitos colaterais no Hibernate.

## Riscos

- Risco mínimo — mudança de uma linha, aditiva, sem alteração de comportamento para os fluxos atuais (que não usam `addVehicle`/`removeVehicle`).

## Dependências

Nenhuma. Agrupar com **[[M2-split-user-service]]** por proximidade de arquivo (`User.java` já estará sendo tocado durante o split do `UserService`), mas pode ser feito de forma totalmente independente se necessário.

## Estimativa

0,25 dia.

## Checklist

- [ ] Analisar código atual
- [ ] Implementar solução
- [ ] Adicionar testes
- [ ] Atualizar documentação
- [ ] Executar testes de regressão
- [ ] Abrir PR
