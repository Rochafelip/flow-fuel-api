# Compartilhamento de Veículo — Backend

> Escopo cruza dois repositórios: `flowfuel` (este repositório, backend) e
> `flowfuel-app` (Android, cliente). Esta spec cobre só o backend — o plano de
> implementação correspondente
> (`docs/superpowers/plans/2026-07-14-vehicle-share-backend.md`) também cobre
> só backend. A parte do cliente Android (telas de convidar/aceitar/recusar,
> tag "Emprestado" na lista de veículos, tela mínima do convidado) é uma spec
> separada no repositório `flowfuel-app`, que depende do contrato definido
> aqui.

**Data:** 2026-07-14
**Status:** aprovado

## Contexto

Quando um usuário empresta o carro físico pra outra pessoa por um dia (ou
mais), hoje não há como essa pessoa registrar abastecimentos/despesas/odômetro
no FlowFuel — o dono só teria como confiar na memória de quem usou o carro, ou
perde o registro daquele período. O objetivo desta feature é deixar o
dono compartilhar temporariamente o veículo com outro usuário FlowFuel, que
passa a poder lançar eventos e atualizar o odômetro naquele veículo enquanto o
compartilhamento estiver ativo — sem ganhar acesso a mais nada do veículo ou
da conta do dono.

Esta spec havia sido antecipada como caso de uso futuro na spec de
[fundação de push notification](2026-07-14-fcm-push-notification-foundation-design.md)
("convite de compartilhamento de veículo por CPF"), mas na prática o convite é
por **email**, não CPF: o `User` deste backend não tem nenhum campo de
CPF nem `username`, só `email` (único) e `name` (não único, não serve como
identificador).

Fora de escopo: mais de um convidado ativo por veículo simultaneamente
(o carro físico só está com uma pessoa de cada vez); notificação push pro
dono a cada evento lançado pelo convidado (ele confere depois pelo histórico);
convidado ver/editar histórico existente ou dados cadastrais do veículo;
convite pra quem ainda não tem conta FlowFuel (a pessoa precisa já estar
cadastrada).

## Decisões

1. **Nova entidade `VehicleShare`** (tabela `vehicle_shares`): `id`,
   `vehicle` (FK), `owner` (FK `User`), `guest` (FK `User`), `status`
   (enum: `PENDING`, `ACTIVE`, `REJECTED`, `REVOKED`, `EXPIRED` — mas
   `EXPIRED` nunca é escrito, ver decisão 2), `createdAt`, `respondedAt`
   (nullable), `expiresAt` (nullable até o aceite).
2. **Expiração lazy, sem job/scheduler.** Não existe um processo em
   background marcando shares como `EXPIRED`. Em vez disso, um método
   `isCurrentlyActive()` na entidade (`status == ACTIVE && expiresAt.isAfter(now)`)
   é a única fonte de verdade usada em toda leitura (autorização e listagens).
   Um `ACTIVE` com prazo vencido nunca é reescrito no banco — simplesmente
   deixa de contar como ativo nas consultas. O valor `EXPIRED` do enum existe
   só para eventual exibição futura no histórico do dono; hoje nenhum código
   grava esse status.
3. **Regra "um compartilhamento por vez por veículo"**: índice único parcial
   em `vehicle_id` onde `status IN ('PENDING', 'ACTIVE')`. Uma nova tentativa
   de convite enquanto já existe um `PENDING`/`ACTIVE` falha com
   `ConflictException` (409) antes mesmo de chegar no banco (checagem no
   service).
4. **Identificação do convidado por email.** `POST /vehicle-shares` recebe
   `inviteeEmail`; o service resolve pra um `User` existente via
   `UserRepository.findByEmail`. Não existe fluxo de convite pra quem não tem
   conta — 404 nesse caso.
5. **Novo `VehicleShareController` (`/vehicle-shares`):**
   - `POST /vehicle-shares` — dono cria convite.
     Body: `{ "vehicleId": Long, "inviteeEmail": String, "durationDays": Integer }`.
     Retorna o share criado (`status=PENDING`, `expiresAt=null`). Dispara push
     pro convidado (decisão 8).
   - `POST /vehicle-shares/{id}/accept` — convidado aceita. Só o `guest` do
     share pode chamar. Exige `status == PENDING`, senão `BusinessRuleException`
     (400, "convite não está mais disponível"). Seta `status=ACTIVE`,
     `expiresAt = now + durationDays`, `respondedAt = now`.
   - `POST /vehicle-shares/{id}/reject` — convidado recusa. Mesma checagem de
     `PENDING` e de identidade do `guest`. Seta `status=REJECTED`,
     `respondedAt = now`.
   - `DELETE /vehicle-shares/{id}` — dono revoga. Só o `owner` pode chamar.
     Funciona tanto em `PENDING` (cancela convite) quanto em `ACTIVE`
     (encerra o empréstimo antes do prazo). Seta `status=REVOKED`.
   - `GET /vehicle-shares/vehicle/{vehicleId}` — dono consulta o share atual
     do veículo (o `PENDING`/`ACTIVE` mais recente, se houver) — alimenta a
     tela "Compartilhar" do app mostrando se já há convite em andamento.
   - `GET /vehicle-shares/pending` — convidado lista convites `PENDING`
     endereçados a ele — alimenta a tela de aceitar/recusar aberta a partir da
     push.
   - `GET /vehicle-shares/active-for-me` — convidado lista veículos com share
     `ACTIVE` (via `isCurrentlyActive()`) onde ele é o `guest` — alimenta a
     listagem de veículos "emprestados" no app do convidado.
6. **`AuthorizationHelper.ensureOwnsOrHasGuestAccess(User user, Vehicle vehicle)`**
   (novo método): passa se `ensureOwnsVehicle` passaria, OU se existe um
   `VehicleShare` com `vehicle=vehicle`, `guest=user` e `isCurrentlyActive()`.
   Lança `ForbiddenOperationException` (403) nos mesmos moldes dos métodos
   `ensureOwns*` existentes.
7. **Dois pontos de uso da nova autorização:**
   - `VehicleEventService.create()` troca `ensureOwnsVehicle` por
     `ensureOwnsOrHasGuestAccess`. Checagem extra: se quem chama **não** é o
     `owner` do veículo (ou seja, é o guest), `request.getType()` precisa
     estar em `{FUEL, CAR_WASH, TIRES, OTHER}` — categorias de uso diário.
     Fora desse conjunto, `ForbiddenOperationException` (403), mesmo com share
     ativo.
   - `VehicleService.updateOdometer()` troca `findOwned(user, id)` por uma
     variante que usa `ensureOwnsOrHasGuestAccess` (sem restrição de
     categoria — atualizar odômetro é sempre permitido pro guest ativo).
   - **Sem mudança** em `getById`/`update`/`delete` de eventos, nem em
     `setActiveVehicle`/`deleteVehicle` de veículo — continuam exigindo posse
     direta (`ensureOwnsEvent`/`ensureOwnsVehicle`/`findOwned`), então o guest
     não vê nem edita histórico, nem pode marcar o veículo emprestado como
     "ativo" no sentido do backend (isso é só um estado local no app do
     convidado — o `isActive` da tabela `vehicles` continua escopado a
     `findByUserId` do dono).
8. **Push do convite**, reaproveitando `sendPushToUser` (já existe, ver spec
   de [fundação de push](2026-07-14-fcm-push-notification-foundation-design.md)):
   disparado só em `POST /vehicle-shares`, com
   `{ "title": "...", "body": "...", "deepLink": "flowfuel://vehicle-share/{shareId}", "type": "vehicle_share_invite" }`.
   Aceite, recusa e revogação não disparam push — não há necessidade de
   avisar em tempo real, e o convite já é a única notificação necessária
   nesse fluxo.

## Arquivos afetados

```
flowfuel (backend):
  src/main/java/.../vehicleshare/VehicleShare.java (novo — entidade)
  src/main/java/.../vehicleshare/VehicleShareStatus.java (novo — enum)
  src/main/java/.../vehicleshare/VehicleShareRepository.java (novo)
  src/main/java/.../vehicleshare/VehicleShareService.java (novo)
  src/main/java/.../vehicleshare/VehicleShareController.java (novo)
  src/main/java/.../vehicleshare/dto/VehicleShareRequestDTO.java (novo)
  src/main/java/.../vehicleshare/dto/VehicleShareResponseDTO.java (novo)
  src/main/resources/db/migration/V11__vehicle_shares.sql (novo)
  src/main/java/.../common/AuthorizationHelper.java (novo método ensureOwnsOrHasGuestAccess)
  src/main/java/.../vehicleevent/VehicleEventService.java (create() usa nova autorização + checagem de categoria)
  src/main/java/.../vehicle/VehicleService.java (updateOdometer() usa nova autorização)
```

## Testes

- Unitário de `VehicleShareService`: criar convite (sucesso, email
  inexistente → 404, convite pra si mesmo → 400, veículo já com share
  pendente/ativo → 409); aceitar (sucesso, por quem não é o guest → 403, já
  respondido → 400); recusar (mesmas variações); revogar (por quem não é o
  owner → 403, em `PENDING` e em `ACTIVE`); `isCurrentlyActive()` com
  `expiresAt` no passado retornando `false` sem alterar o `status` persistido.
- Unitário de `AuthorizationHelper.ensureOwnsOrHasGuestAccess`: dono passa;
  guest com share ativo passa; guest com share expirado (lazy) falha; usuário
  sem nenhuma relação falha.
- Unitário de `VehicleEventService.create()`: guest com share ativo lançando
  categoria permitida (sucesso) vs. não permitida (403); dono sempre lançando
  qualquer categoria (sucesso, comportamento inalterado).
- Integração dos endpoints de `VehicleShareController` (fluxo completo:
  convite → aceite → lançar evento como guest → revogar → guest perde acesso).

## Critérios de Aceitação

- Dono convida por email um usuário existente → convidado recebe push e vê o
  convite em `GET /vehicle-shares/pending`.
- Convidado aceita → `GET /vehicle-shares/active-for-me` passa a listar o
  veículo; consegue `POST /vehicle-events` com categoria `FUEL`/`CAR_WASH`/
  `TIRES`/`OTHER` e `PUT /vehicles/{id}/odometer` nesse veículo.
- Convidado tentando lançar `INSURANCE`/`TAX`/`DOCUMENTS`/`MAINTENANCE`/
  `OIL_CHANGE` no veículo emprestado → 403.
- Convidado tentando `GET`/`PUT`/`DELETE` de evento existente, ou editar dados
  do veículo, ou revogar o share → 403 (sem mudança de comportamento — ele
  nunca é o `owner`).
- Dono tenta convidar de novo enquanto já há convite pendente ou ativo → 409.
- Dono revoga um share ativo → convidado perde acesso imediatamente (próxima
  chamada de `ensureOwnsOrHasGuestAccess` falha).
- Prazo (`expiresAt`) vence → convidado perde acesso automaticamente na
  próxima chamada, sem qualquer job rodando em background.
- Convidado recusa o convite → dono pode convidar de novo (não fica travado
  em 409).
