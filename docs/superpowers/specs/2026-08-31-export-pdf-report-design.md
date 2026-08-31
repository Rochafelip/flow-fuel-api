# Exportação em PDF — Relatório Elaborado com Resumo

## Contexto

A exportação de abastecimentos e eventos já existe em produção
(`docs/superpowers/specs/2026-06-25-export-refuels-events-design.md`), com
Strategy Pattern (`CsvExportStrategy`, `ExcelExportStrategy`). Um começo de
suporte a PDF já existe como **trabalho local não commitado** neste
repositório (`ExportFormat.PDF`, `PdfExportStrategy`,
`ExportFileNameBuilder` atualizado, testes correspondentes, e o fix do
Content-Type descrito abaixo) — nada disso está em produção ainda. Esta
spec assume esse WIP como ponto de partida e cobre o que falta para ele
virar um relatório de fato, em vez de repetir o que já foi feito:

1. **Content-Type**: já corrigido no WIP local — `ExportService.contentTypeFor()`
   passou a distinguir os três formatos (documentado abaixo por completude,
   sem trabalho novo a fazer aqui).
2. **Relatório pobre**: `PdfExportStrategy` (WIP) desenha só uma tabela
   crua (cabeçalho + linhas), sem identificação do veículo, período ou
   resumo — a tabela sozinha em PDF não tem vantagem sobre o CSV. **Esta é
   a parte que falta**, coberta pelo restante desta spec.

O frontend web (`flowfuel-frontend`) está trocando a opção "Excel" do
seletor de formato por "PDF" (ver
`docs/superpowers/specs/2026-08-31-export-pdf-frontend-design.md` naquele
repositório) — esta spec cobre o trabalho de backend necessário para que
essa opção valha a pena: corrigir o Content-Type e transformar o PDF em um
relatório de fato (cabeçalho com veículo/período + resumo antes da tabela).

**Fora de escopo:** remover `ExportFormat.XLSX`/`ExcelExportStrategy` do
backend (o endpoint continua aceitando `format=xlsx`; só a UI web deixa de
oferecer o botão) e exportação por múltiplos veículos ou relatório
financeiro mensal (sub-specs futuras da Fase 5, não tocadas aqui).

## Objetivo

Ao exportar abastecimentos ou eventos em PDF (`format=pdf`), o usuário recebe
um relatório com: título, identificação do veículo, período consultado, um
resumo (totais/médias) e a tabela de registros — com o `Content-Type`
correto na resposta HTTP.

## Content-Type (já corrigido no WIP local)

`ExportService.contentTypeFor(ExportFormat)` (linha 171), antes do WIP local, era:

```java
private String contentTypeFor(ExportFormat format) {
    return format == ExportFormat.XLSX
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "text/csv";
}
```

Já virou (no WIP local) um `switch` exaustivo sobre os três valores do enum:

```java
private String contentTypeFor(ExportFormat format) {
    return switch (format) {
        case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        case PDF -> "application/pdf";
        case CSV -> "text/csv";
    };
}
```

## Fórmula de consumo médio compartilhada

`DashboardService.calculateAverageConsumption(List<Refuel>)` (linhas
239-269) já implementa a "fórmula oficial de consumo médio do produto"
(pares consecutivos de abastecimentos tanque-cheio, `SUM(kmDriven) /
SUM(energyUsed)`, ignorando `kmSinceLastRefuel` persistido). O resumo do PDF
precisa do mesmo número — para não haver dois lugares calculando "consumo
médio" com potencial de divergir, o método é extraído para uma classe nova:

```text
refuel/
└── RefuelConsumptionCalculator.java   (classe utilitária, método estático)
```

```java
public final class RefuelConsumptionCalculator {
    private RefuelConsumptionCalculator() {}

    public static Double calculateAverageConsumption(List<Refuel> fullTankRefuelsDesc) {
        // corpo idêntico ao método privado atual de DashboardService,
        // incluindo o Javadoc existente (fórmula, exemplo, casos de borda)
    }
}
```

`DashboardService` passa a chamar
`RefuelConsumptionCalculator.calculateAverageConsumption(...)` no lugar do
método privado removido — comportamento inalterado, mesmo Javadoc
preservado na nova classe. A entrada continua sendo a lista de
abastecimentos tanque-cheio **ordenada do mais recente ao mais antigo**
(mesma pré-condição documentada hoje).

## `ExportStrategy` — parâmetro de metadata

A interface ganha um terceiro parâmetro, usado apenas pelo PDF:

```java
public record ExportMetadata(String vehicleLabel, String periodLabel, List<String> summaryLines) {
    public static final ExportMetadata EMPTY = new ExportMetadata("", "", List.of());
}

public interface ExportStrategy {
    ExportFormat supportedFormat();
    byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata);
}
```

- `CsvExportStrategy` e `ExcelExportStrategy` recebem o parâmetro e o
  ignoram (assinatura muda, corpo não) — mantém o despacho polimórfico via
  `Map<ExportFormat, ExportStrategy>` já existente em `ExportService`, sem
  `if (format == PDF)` espalhado pelo service.
- `ExportService` só monta um `ExportMetadata` não-vazio quando
  `format == ExportFormat.PDF`; para CSV/XLSX passa `ExportMetadata.EMPTY`
  sem gastar ciclos calculando resumo.

## `PdfExportStrategy` — layout do relatório

Estrutura atual (título ausente, só tabela) passa a ser:

```text
[Título fixo: "Relatório de Abastecimentos" ou "Relatório de Eventos"]
[metadata.vehicleLabel()]
[metadata.periodLabel()]

[uma linha por item de metadata.summaryLines()]

[tabela: cabeçalho em negrito + linhas — layout atual inalterado]
```

Implementação com os mesmos elementos OpenPDF já usados (`Document`,
`Paragraph`/`Phrase`, `Font`), sem dependência nova. Página continua A4
paisagem (`PageSize.A4.rotate()`), margens 24pt.

- Título: fonte maior, negrito (reaproveita padrão de `HEADER_FONT`, escala
  maior).
- Vehicle label / period label: fonte normal, cinza/preto.
- `summaryLines`: mesma fonte do corpo, uma por linha.
- Tabela: sem mudanças no código existente de `headerCell`/`bodyCell`.

O título ("Relatório de Abastecimentos" vs "Relatório de Eventos") é
passado como uma string fixa por `ExportService`, junto do restante do
`ExportMetadata` — mais simples que inferir do formato dos `headers`, então
`ExportMetadata` ganha um quarto campo `reportTitle`:

```java
public record ExportMetadata(String reportTitle, String vehicleLabel, String periodLabel, List<String> summaryLines) {
    public static final ExportMetadata EMPTY = new ExportMetadata("", "", "", List.of());
}
```

## `ExportService` — construção do metadata

### Labels

```java
private String vehicleLabel(Vehicle vehicle) {
    String plate = vehicle.getLicensePlate();
    String base = vehicle.getBrand() + " " + vehicle.getModel();
    return (plate != null && !plate.isBlank()) ? base + " — " + plate : base;
}

private String periodLabel(LocalDate startDate, LocalDate endDate) {
    if (startDate == null || endDate == null) {
        return "Todo o histórico";
    }
    return startDate.format(DATE_FORMAT) + " – " + endDate.format(DATE_FORMAT);
}
```

(`DATE_FORMAT` é o `dd/MM/yyyy` já existente na classe.)

### Resumo de abastecimentos

Calculado a partir da mesma lista `refuels` já buscada em
`exportRefuels` (respeita o filtro de período já aplicado):

```java
BigDecimal totalSpent = refuels.stream().map(Refuel::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal totalEnergy = refuels.stream().map(Refuel::getEnergyAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
List<Refuel> fullTankDesc = refuels.stream().filter(Refuel::getFullTank).toList(); // já vem desc por refuelDate
Double averageConsumption = RefuelConsumptionCalculator.calculateAverageConsumption(fullTankDesc);

List<String> summaryLines = List.of(
    "Total gasto: R$ " + formatDecimal(totalSpent),
    "Total abastecido: " + formatDecimal(totalEnergy) + " " + vehicle.getEnergyUnit(),
    "Consumo médio: " + (averageConsumption > 0 ? formatDecimal(averageConsumption) : "-") + " " + vehicle.getConsumptionUnit(),
    "Abastecimentos: " + refuels.size()
);
```

`formatDecimal` é um novo helper privado — `"%.2f".formatted(valor).replace('.', ',')`
— reaproveitado também para eventos, mantendo o padrão pt-BR já usado no
resto do service (`toPlainString()` das linhas da tabela não muda; o resumo
usa vírgula decimal por ser texto de exibição, não dado tabular).

### Resumo de eventos

```java
BigDecimal totalSpent = events.stream()
        .map(e -> e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

Map<VehicleEventType, Long> countByType = events.stream()
        .collect(Collectors.groupingBy(VehicleEvent::getType, Collectors.counting()));

List<String> summaryLines = new ArrayList<>();
summaryLines.add("Total gasto: R$ " + formatDecimal(totalSpent));
summaryLines.add("Eventos: " + events.size());
countByType.entrySet().stream()
        .sorted(Map.Entry.<VehicleEventType, Long>comparingByValue().reversed())
        .forEach(entry -> summaryLines.add(entry.getKey().name() + ": " + entry.getValue()));
```

`VehicleEventType` (`vehicleevent/VehicleEventType.java`) é um enum sem
label legível (`FUEL`, `MAINTENANCE`, `OIL_CHANGE`, ...) — `toRow()` já usa
`event.getType().name()` na tabela hoje, então o resumo segue a mesma
convenção por consistência, sem introduzir um mapeamento de labels que não
existe em nenhum outro lugar do backend (o frontend é quem já traduz esses
nomes para exibição, via `VEHICLE_EVENT_TYPE_LABELS`).

### Montagem final

```java
ExportMetadata metadata = format == ExportFormat.PDF
        ? new ExportMetadata("Relatório de Abastecimentos", vehicleLabel(vehicle), periodLabel(startDate, endDate), summaryLines)
        : ExportMetadata.EMPTY;
byte[] content = strategiesByFormat.get(format).export(REFUEL_HEADERS, rows, metadata);
```

(equivalente em `exportEvents`, título `"Relatório de Eventos"`).

## Erros

Nenhuma mudança na matriz de erros já documentada na spec original — os
mesmos 400/403/404 continuam válidos, `format=pdf` passa pela mesma
validação de `ExportFormat.fromString`.

## Testes

- **`RefuelConsumptionCalculatorTest`** (novo): migra os casos hoje
  cobertos indiretamente via `DashboardServiceTest` para testar a classe
  extraída diretamente — pares válidos, menos de 2 refuels tanque-cheio,
  energia total zero.
- **`DashboardServiceTest`** (existente): ajustar apenas a forma de
  chamar/mockar caso o teste acesse o método privado por reflection; se já
  testa via método público que o invoca, nenhuma mudança de asserção.
- **`ExportServiceTest`** (existente, estender): novos casos —
  `exportRefuels` com `format=pdf` gera `ExportMetadata` com
  `vehicleLabel`/`periodLabel`/`summaryLines` corretos (incluindo o caso
  "todo o histórico" quando datas nulas, e consumo médio `-` quando não há
  refuels tanque-cheio suficientes); `exportRefuels`/`exportEvents` com
  `format=csv` chamam a strategy com `ExportMetadata.EMPTY`.
- **`PdfExportStrategyTest`** (novo): smoke test — chama `export(...)` com
  headers/rows/metadata de exemplo e verifica que o retorno é um PDF válido
  não vazio (ex.: bytes começam com `%PDF-`). Não valida layout pixel a
  pixel — mesmo nível de rigor já aceito no projeto para geração de PDF.
- **`ExportControllerIntegrationTest`** (existente): o WIP local já cobre o
  Content-Type no nível de `ExportServiceTest` (unit), mas não no
  controller/HTTP; adicionar um caso de sucesso `format=pdf` que confere
  `Content-Type: application/pdf` na resposta real do MockMvc, cobrindo a
  camada que o teste unitário não alcança.

## Pontos de Atenção

- O resumo de eventos usa `VehicleEventType.name()` (mesmo valor cru já
  usado na tabela hoje) por não haver label legível nesse enum — a
  tradução para texto amigável é responsabilidade do cliente que exibe o
  arquivo, igual já acontece para a tabela.
- `RefuelConsumptionCalculator` é extração pura (mesmo comportamento,
  mesmo Javadoc) — não é uma mudança de fórmula, só de localização, para
  evitar duas implementações divergentes do "consumo médio oficial".
- O `Content-Type` do PDF (`application/pdf`) é o único content-type novo;
  `ExportFileNameBuilder` já gera a extensão `.pdf` corretamente (linha 21),
  não precisa de mudança.
