# Exportação de Abastecimentos e Eventos (CSV/XLSX) — Spec 1/3

## Contexto

O roadmap "Fase 5 — Exportação Inteligente de Dados" descreve um épico com 12 histórias
(5.1–5.12), cobrindo desde exportação simples de abastecimentos/eventos até relatórios
financeiros mensais, snapshot de dashboard e um endpoint unificado multi-tipo. É grande
demais para um único plano de implementação, então foi dividido em 3 sub-specs sequenciais:

1. **Esta spec** — Exportação de Refuels e Events em CSV/XLSX (Histórias 5.1, 5.2, 5.4, 5.9, 5.12).
2. Próxima — Relatório Full consolidado + XLSX multi-aba (Histórias 5.3, 5.10).
3. Depois — Relatório Financeiro Mensal, Dashboard Snapshot, exportação por todos os
   veículos e endpoint unificado (Histórias 5.5, 5.6, 5.7, 5.8, 5.11).

## Objetivo

Permitir que o usuário autenticado exporte, para um veículo específico de sua propriedade,
a lista de abastecimentos ou de eventos do veículo em formato CSV ou XLSX, com filtro
opcional de período.

## Endpoints

```
GET /exports/refuels?vehicleId={id}&startDate={LocalDate}&endDate={LocalDate}&format=csv|xlsx
GET /exports/events?vehicleId={id}&type={VehicleEventType}&startDate={LocalDate}&endDate={LocalDate}&format=csv|xlsx
```

- Seguem o padrão dos controllers existentes (`RefuelController`, `VehicleEventController`):
  sem prefixo `/api` (o projeto não usa `server.servlet.context-path`), `@AuthenticationPrincipal User`,
  parâmetros via `@RequestParam`.
- `vehicleId` é **obrigatório** nesta spec. Exportação "todos os veículos" (História 5.5/5.8)
  fica para a Spec 3.
- `format` é obrigatório, valores aceitos: `csv`, `xlsx` (case-insensitive). Qualquer outro
  valor → 400 (`ErrorCode.VALIDATION_FAILED`).
- `startDate`/`endDate` opcionais; se ambos ausentes, exporta todo o histórico do veículo.
  Se apenas um for informado, ou se `startDate > endDate` → 400 (`VALIDATION_FAILED`).
- `type` (apenas em `/exports/events`) opcional, filtra por `VehicleEventType`.
- Autorização: `vehicleId` deve pertencer ao usuário autenticado, validado via
  `AuthorizationHelper.ensureOwnsVehicle` (mesmo padrão usado em `RefuelService`/
  `VehicleEventService`). Veículo inexistente → 404 (`ResourceNotFoundException`);
  veículo de outro usuário → 403 (`ForbiddenOperationException`).

### Resposta

Síncrona — o arquivo é gerado e devolvido diretamente na resposta HTTP (`ResponseEntity<byte[]>`).
Volume esperado (uso pessoal, centenas/poucos milhares de registros por veículo) não justifica
processamento assíncrono.

- `Content-Type`: `text/csv` (CSV) ou `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (XLSX).
- `Content-Disposition: attachment; filename="<nome-gerado>"` — ver convenção de nome abaixo.

## Colunas exportadas

**Refuels** (`GET /exports/refuels`): Data, Combustível (tipo), Litros/kWh, Preço/unidade, Total, Odômetro.

> `[INFERIDO — confirmar com time]` A História 5.1 do roadmap pede também a coluna "Posto",
> mas a entidade `Refuel` não possui esse campo hoje. Adicionar o campo é uma mudança de
> modelo fora do escopo desta spec de exportação — a coluna é omitida por ora.

**Events** (`GET /exports/events`): Data, Tipo, Descrição, Valor, Odômetro.

Cada formato (CSV/XLSX) recebe a mesma lista de `String[]` (cabeçalho) + linhas, já formatada
como texto (datas em `dd/MM/yyyy`, valores monetários com 2 casas decimais) antes de chegar
às strategies — strategies não fazem formatação de domínio, só estrutura do arquivo.

## Arquitetura

Strategy Pattern, conforme sugerido no roadmap, sob o novo pacote `export`:

```
export/
├── ExportController.java
├── ExportService.java
├── dto/
│   └── ExportFormat.java        (enum: CSV, XLSX — com parsing case-insensitive a partir da query string)
├── strategy/
│   ├── ExportStrategy.java      (interface: ExportFormat supportedFormat(); byte[] export(String[] headers, List<String[]> rows))
│   ├── CsvExportStrategy.java
│   └── ExcelExportStrategy.java
└── util/
    └── ExportFileNameBuilder.java
```

- `ExportStrategy` é um bean Spring (`@Component`). `ExportService` recebe `List<ExportStrategy>`
  injetado e seleciona pelo `supportedFormat()` — sem `if/else` de formato no service.
- `ExportService.exportRefuels(user, vehicleId, startDate, endDate, format)` e
  `exportEvents(user, vehicleId, type, startDate, endDate, format)`:
  1. Busca e valida o veículo (reaproveita `VehicleRepository` + `AuthorizationHelper`, igual a
     `RefuelService`/`VehicleEventService`).
  2. Busca os registros via repository (sem paginação — exporta tudo do filtro).
  3. Mapeia entidades → `String[]` de linha (datas/valores formatados).
  4. Resolve a `ExportStrategy` pelo `format` e gera os bytes.
  5. Monta o nome do arquivo via `ExportFileNameBuilder`.
- `ExportController` monta o `ResponseEntity` com headers e devolve os bytes.

### Acesso a dados

- `RefuelRepository` já expõe `findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(vehicleId, start, end)`
  (List, sem paginação) e `findByVehicleIdOrderByRefuelDateDesc(vehicleId)` (List) — reaproveitados
  como estão.
- `VehicleEventRepository` só tem variantes paginadas (`Page<...>`). Esta spec adiciona métodos
  `List`-based equivalentes (`findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc`,
  `findByVehicleIdAndTypeOrderBy...`, `findByVehicleIdAndEventDateBetweenOrderBy...`,
  `findByVehicleIdAndTypeAndEventDateBetweenOrderBy...`), seguindo a mesma ordenação e
  convenção de nomes dos métodos paginados já existentes.

### Apache POI (XLSX)

- `XSSFWorkbook` em memória (não streaming) — suficiente para o volume esperado e permite
  todos os recursos pedidos pela História 5.9 sem complicação de flush manual.
- Recursos aplicados em `ExcelExportStrategy`:
  - Cabeçalho em negrito (estilo de fonte).
  - `sheet.autoSizeColumn(i)` para cada coluna.
  - `sheet.createFreezeazePane(0, 1)` — congela a primeira linha.
  - `sheet.setAutoFilter(...)` sobre o intervalo de cabeçalho.
- Dependência nova no `pom.xml`: `org.apache.poi:poi-ooxml`.

### Convenção de nome de arquivo (História 5.12)

`ExportFileNameBuilder.build(resourceType, vehicle, format)` gera:

```
flowfuel-{resourceType}-{slug-do-veiculo}-{ano-atual}.{ext}
```

Exemplo: `flowfuel-refuels-corolla-2026.csv`. O slug do veículo é derivado de um campo de
identificação existente (ex.: marca+modelo ou nome, a confirmar no `Vehicle` ao implementar)
normalizado para `kebab-case` sem acentos. `{ano-atual}` é o ano corrente no momento da
exportação (não o ano dos dados — roadmap não especifica isso, e usar o ano da exportação é a
leitura mais simples do exemplo dado).

## Erros

| Cenário | Erro |
|---|---|
| `vehicleId` não existe | 404 `ResourceNotFoundException` |
| `vehicleId` pertence a outro usuário | 403 `ForbiddenOperationException` (`AuthorizationHelper.ensureOwnsVehicle`) |
| `format` ausente ou inválido | 400 `VALIDATION_FAILED` |
| Apenas um de `startDate`/`endDate` informado, ou `startDate > endDate` | 400 `VALIDATION_FAILED` |

Todos reaproveitam o `GlobalExceptionHandler` já existente — nenhum novo `ExceptionHandler` é necessário.

## Testes

- **Unit — strategies**: `CsvExportStrategy` e `ExcelExportStrategy` recebem headers + linhas
  e geram bytes; teste relê o conteúdo (parse CSV / abrir workbook POI) e valida células,
  cabeçalho, freeze pane e autofilter.
- **Unit — ExportService**: aplica corretamente os filtros de data/tipo, seleciona a strategy
  certa pelo `format`, propaga erros de autorização/validação.
- **Integration (`@SpringBootTest` + MockMvc)**: GET nos dois endpoints — happy path (200,
  `Content-Type` e `Content-Disposition` corretos), 403 (veículo de outro usuário), 404
  (veículo inexistente), 400 (formato inválido, datas invertidas).

## Pontos de Atenção

- Coluna "Posto" da História 5.1 omitida por ausência do campo no modelo atual — ver nota `[INFERIDO]` acima.
- "Ano" no nome do arquivo (História 5.12) interpretado como ano da exportação, não dos dados — roadmap ambíguo nesse ponto.
- Exportação multi-veículo (`allVehicles`) e endpoint unificado (`GET /exports`) ficam para a Spec 3 — não fazem parte desta entrega.
