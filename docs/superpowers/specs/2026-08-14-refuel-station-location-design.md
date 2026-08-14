# Design: Localização do posto no abastecimento

**Data:** 2026-08-14
**Status:** Aprovado

## Contexto

Feature pedida pelo usuário: guardar em qual posto cada abastecimento foi feito. Decidido com o usuário via brainstorm (repo `flowfuel-frontend`) que:

- O posto é escolhido pelo usuário a partir da lista de postos próximos já existente (`GET /stations/nearby`), não capturado automaticamente por GPS nem digitado como texto livre.
- É opcional — abastecimentos sem posto continuam válidos.
- Não existe entidade `Station` persistida (postos vêm ao vivo do Overpass/Open Charge Map, identificados por `placeId` efêmero), então não dá pra usar uma foreign key. A solução é guardar um **snapshot** dos dados do posto escolhido (nome, endereço, coordenadas) direto no `Refuel`, no momento da seleção.

Este design cobre só o backend. O consumo no frontend (diálogo de seleção, formulário, exibição na lista) é um spec/plano separado no repo `flowfuel-frontend`.

## Escopo

- `refuel/Refuel.java` — 4 novas colunas opcionais.
- `refuel/RefuelRequestDTO.java` / `RefuelResponseDTO.java` — mesmos 4 campos, opcionais.
- `refuel/RefuelService.java` — grava os campos em `createRefuel`/`updateRefuel`.
- `db/migration/V14__refuel_station.sql` — nova migration.

Fora de escopo: qualquer mudança em `station/` (o endpoint `/stations/nearby` já retorna tudo que é preciso); validação de que o posto escolhido realmente é "próximo" do odômetro/data do abastecimento (o usuário pode escolher qualquer posto retornado pela busca, sem checagem cruzada de distância/tempo — não é um requisito de negócio, seria complexidade sem valor claro).

---

## 1. Migration `V14__refuel_station.sql`

```sql
ALTER TABLE refuels
    ADD COLUMN station_name VARCHAR(255),
    ADD COLUMN station_address VARCHAR(500),
    ADD COLUMN station_latitude DOUBLE PRECISION,
    ADD COLUMN station_longitude DOUBLE PRECISION;
```

Todas nullable (sem `NOT NULL`), sem default — abastecimentos existentes ficam com essas colunas `NULL`, que é exatamente o estado "sem posto".

## 2. `Refuel.java`

Adiciona, junto aos outros `@Column`:

```java
@Column(name = "station_name")
private String stationName;

@Column(name = "station_address")
private String stationAddress;

@Column(name = "station_latitude")
private Double stationLatitude;

@Column(name = "station_longitude")
private Double stationLongitude;
```

## 3. `RefuelRequestDTO.java`

Adiciona, sem anotação de validação (campos opcionais):

```java
private String stationName;
private String stationAddress;
private Double stationLatitude;
private Double stationLongitude;
```

## 4. `RefuelResponseDTO.java`

Adiciona os mesmos 4 campos, e inclui no `from(Refuel r)`:

```java
.stationName(r.getStationName())
.stationAddress(r.getStationAddress())
.stationLatitude(r.getStationLatitude())
.stationLongitude(r.getStationLongitude())
```

## 5. `RefuelService.java`

**`createRefuel`**: logo após `refuel.setVehicle(vehicle);`, adiciona:

```java
refuel.setStationName(request.getStationName());
refuel.setStationAddress(request.getStationAddress());
refuel.setStationLatitude(request.getStationLatitude());
refuel.setStationLongitude(request.getStationLongitude());
```

**`updateRefuel`**: ao contrário dos outros campos do método (que só atualizam se vierem `!= null`, porque o PUT hoje é parcial-por-convenção), os campos de posto são **sempre** atribuídos com o que veio na requisição — inclusive `null` — porque o frontend sempre manda o estado atual do posto (selecionado ou removido) no corpo inteiro, e "remover o posto" precisa ser um resultado possível de uma edição:

```java
refuel.setStationName(request.getStationName());
refuel.setStationAddress(request.getStationAddress());
refuel.setStationLatitude(request.getStationLatitude());
refuel.setStationLongitude(request.getStationLongitude());
```

(colocar essas 4 linhas fora dos blocos `if (request.getX() != null)` existentes, direto no corpo do método, antes do `return`).

## Fora de escopo / riscos aceitos

- Sem migração de dados históricos (não há como inferir o posto de abastecimentos já registrados).
- Sem testes automatizados no projeto para este módulo além dos já existentes; validação por build (`mvn -q compile` ou equivalente) e pelos testes existentes de `RefuelService`/`RefuelController`, se houver, rodando `mvn test`.
