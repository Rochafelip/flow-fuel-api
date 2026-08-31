# Relatório PDF de Exportação Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transformar a exportação em PDF (hoje tabela crua) em um relatório com título, identificação do veículo, período e resumo (totais/consumo médio), e corrigir o `Content-Type` da resposta.

**Architecture:** O repositório já tem um WIP local não commitado (bare-table PDF + fix de Content-Type) — o Task 1 commita esse baseline. Em seguida, `ExportStrategy.export(...)` ganha um parâmetro `ExportMetadata` (título, label do veículo, label do período, linhas de resumo) que só o `PdfExportStrategy` usa; `ExportService` monta esse metadata a partir dos mesmos dados já buscados para a tabela. A fórmula de consumo médio, hoje duplicável, é extraída para `RefuelConsumptionCalculator` e reusada tanto pelo dashboard quanto pelo resumo do PDF.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, OpenPDF (`com.lowagie:openpdf`, já adicionado ao `pom.xml` pelo WIP), Maven (`./mvnw`).

---

### Task 1: Commit do WIP local (PDF bare-table + fix de Content-Type)

**Files:** nenhum arquivo novo — apenas commit do que já está no working tree.

- [ ] **Step 1: Conferir o que está pendente**

Run: `git status --short`
Expected: mostra modificações em `pom.xml`, `ExportFormat.java`, `ExportService.java`, `ExportFileNameBuilder.java`, testes correspondentes, e os arquivos novos `PdfExportStrategy.java`/`PdfExportStrategyTest.java`.

- [ ] **Step 2: Rodar a suíte de testes para confirmar que o WIP já está verde**

Run: `./mvnw test -Dtest=ExportServiceTest,ExportFormatTest,ExportFileNameBuilderTest,ExportControllerIntegrationTest,com.devappmobile.flowfuel.export.strategy.*`
Expected: BUILD SUCCESS, todos os testes passam.

- [ ] **Step 3: Commit**

```bash
git add pom.xml \
  src/main/java/com/devappmobile/flowfuel/export/ExportFormat.java \
  src/main/java/com/devappmobile/flowfuel/export/ExportService.java \
  src/main/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilder.java \
  src/main/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategy.java \
  src/test/java/com/devappmobile/flowfuel/export/ExportControllerIntegrationTest.java \
  src/test/java/com/devappmobile/flowfuel/export/ExportFormatTest.java \
  src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java \
  src/test/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilderTest.java \
  src/test/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategyTest.java
git commit -m "feat(export): habilitar formato PDF (tabela simples) e corrigir Content-Type"
```

---

### Task 2: Extrair `RefuelConsumptionCalculator`

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/refuel/RefuelConsumptionCalculator.java`
- Test: `src/test/java/com/devappmobile/flowfuel/refuel/RefuelConsumptionCalculatorTest.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java:99,204,218-269`

**Interfaces:**
- Produces: `RefuelConsumptionCalculator.calculateAverageConsumption(List<Refuel>): Double` — usado por `DashboardService` (Task 2) e por `ExportService` (Task 5).

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.refuel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RefuelConsumptionCalculatorTest {

    private Refuel refuel(int odometer, double energyAmount) {
        Refuel refuel = new Refuel();
        refuel.setOdometer(odometer);
        refuel.setEnergyAmount(BigDecimal.valueOf(energyAmount));
        refuel.setFullTank(true);
        refuel.setRefuelDate(LocalDateTime.now());
        return refuel;
    }

    @Test
    void calculateAverageConsumption_tresAbastecimentosTanqueCheio_aplicaFormulaOficial() {
        Refuel refuelC = refuel(3000, 40.0);
        Refuel refuelB = refuel(2200, 35.0);
        Refuel refuelA = refuel(1500, 30.0);

        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuelC, refuelB, refuelA));

        assertThat(result).isEqualTo(20.0);
    }

    @Test
    void calculateAverageConsumption_menosDeDoisAbastecimentos_retornaZero() {
        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuel(3000, 40.0)));

        assertThat(result).isEqualTo(0.0);
    }

    @Test
    void calculateAverageConsumption_semParesValidos_retornaZero() {
        Refuel refuelB = refuel(2000, 35.0);
        Refuel refuelA = refuel(2000, 30.0);

        Double result = RefuelConsumptionCalculator.calculateAverageConsumption(
                List.of(refuelB, refuelA));

        assertThat(result).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RefuelConsumptionCalculatorTest`
Expected: FAIL — compilation error, `RefuelConsumptionCalculator` unresolved.

- [ ] **Step 3: Write minimal implementation**

```java
package com.devappmobile.flowfuel.refuel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class RefuelConsumptionCalculator {

    private RefuelConsumptionCalculator() {
    }

    /**
     * Fórmula oficial de consumo médio do produto.
     *
     * <p>Para cada par consecutivo de abastecimentos tanque-cheio ordenados do mais recente
     * ao mais antigo (current, previous), calcula:
     * <pre>
     *   kmDriven   = current.odometer - previous.odometer
     *   energyUsed = current.energyAmount
     * </pre>
     * O consumo final é {@code SUM(kmDriven) / SUM(energyUsed)} sobre todos os pares válidos
     * (kmDriven > 0 e energyUsed > 0), arredondado para 2 casas decimais (HALF_UP).
     *
     * <p>Exemplo: 3 abastecimentos [C(3000 km, 40 L), B(2200 km, 35 L), A(1500 km, 30 L)]
     * → pares: (C-B): 800 km / 40 L; (B-A): 700 km / 35 L
     * → consumo = (800+700) / (40+35) = 1500/75 = 20,00 km/L
     *
     * <p>Retorna 0.0 se houver menos de 2 abastecimentos tanque-cheio ou energia total zero.
     *
     * <p>Nota: o campo {@code kmSinceLastRefuel} persistido na entidade Refuel é deliberadamente
     * ignorado para garantir consistência com o odômetro registrado pelo usuário.
     */
    public static Double calculateAverageConsumption(List<Refuel> fullRefuels) {
        if (fullRefuels.size() < 2) {
            return 0.0;
        }

        double totalKm = 0;
        double totalEnergyUsed = 0;

        for (int i = 0; i < fullRefuels.size() - 1; i++) {
            Refuel current = fullRefuels.get(i);
            Refuel previous = fullRefuels.get(i + 1);

            double kmDriven = current.getOdometer() - previous.getOdometer();
            double energyUsed = current.getEnergyAmount().doubleValue();

            if (kmDriven > 0 && energyUsed > 0) {
                totalKm += kmDriven;
                totalEnergyUsed += energyUsed;
            }
        }

        if (totalEnergyUsed == 0) {
            return 0.0;
        }

        double consumption = totalKm / totalEnergyUsed;

        return BigDecimal.valueOf(consumption)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=RefuelConsumptionCalculatorTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Fazer `DashboardService` delegar para a classe extraída**

Em `src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java`:

Adicionar import:
```java
import com.devappmobile.flowfuel.refuel.RefuelConsumptionCalculator;
```

Trocar (linha 99):
```java
            Double averageConsumption = calculateAverageConsumption(
                    refuelRepository.findFullTankRefuelsByVehicleId(vehicleId));
```
por:
```java
            Double averageConsumption = RefuelConsumptionCalculator.calculateAverageConsumption(
                    refuelRepository.findFullTankRefuelsByVehicleId(vehicleId));
```

Trocar (linha 204):
```java
        Double averageConsumption = calculateAverageConsumption(
                refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(vehicleId, type));
```
por:
```java
        Double averageConsumption = RefuelConsumptionCalculator.calculateAverageConsumption(
                refuelRepository.findFullTankRefuelsByVehicleIdAndRefuelType(vehicleId, type));
```

Remover o método privado inteiro (Javadoc incluso, linhas 218-269, do comentário `/**\n     * Fórmula oficial...` até o fechamento `}` de `calculateAverageConsumption`) — o corpo já foi movido para `RefuelConsumptionCalculator` no Step 3.

- [ ] **Step 6: Rodar os testes de dashboard e export para confirmar que nada quebrou**

Run: `./mvnw test -Dtest=DashboardServiceTest,ExportServiceTest`
Expected: PASS — `DashboardServiceTest` não referencia o método privado diretamente (só via `getVehicleDashboard`), então nenhuma asserção muda.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/refuel/RefuelConsumptionCalculator.java \
  src/test/java/com/devappmobile/flowfuel/refuel/RefuelConsumptionCalculatorTest.java \
  src/main/java/com/devappmobile/flowfuel/dashboard/DashboardService.java
git commit -m "refactor(refuel): extrair fórmula de consumo médio para RefuelConsumptionCalculator"
```

---

### Task 3: `ExportMetadata` + `ExportStrategy` ganham parâmetro de metadata

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/strategy/ExportMetadata.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/export/strategy/ExportStrategy.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategy.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategy.java`
- Modify: `src/main/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategy.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategyTest.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategyTest.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategyTest.java`

**Interfaces:**
- Produces: `ExportMetadata(reportTitle, vehicleLabel, periodLabel, summaryLines)` record com `ExportMetadata.EMPTY` — usado por `ExportService` (Task 5) e por todas as strategies.
- Este task só muda a assinatura (metadata ignorado por CSV/Excel/PDF); o PDF passa a *usar* o conteúdo no Task 4.

- [ ] **Step 1: Create `ExportMetadata`**

```java
package com.devappmobile.flowfuel.export.strategy;

import java.util.List;

public record ExportMetadata(String reportTitle, String vehicleLabel, String periodLabel, List<String> summaryLines) {

    public static final ExportMetadata EMPTY = new ExportMetadata("", "", "", List.of());
}
```

- [ ] **Step 2: Atualizar a interface `ExportStrategy`**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;

import java.util.List;

public interface ExportStrategy {

    ExportFormat supportedFormat();

    byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata);
}
```

- [ ] **Step 3: Atualizar `CsvExportStrategy.export(...)` (ignora o parâmetro)**

Em `CsvExportStrategy.java`, trocar a assinatura:
```java
    public byte[] export(String[] headers, List<String[]> rows) {
```
por:
```java
    public byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata) {
```
(corpo do método inalterado).

- [ ] **Step 4: Atualizar `CsvExportStrategyTest` para a nova assinatura**

Em `CsvExportStrategyTest.java`, trocar as três chamadas:
```java
        byte[] result = strategy.export(headers, rows);
```
(ocorre 2x, com `rows` diferente em cada teste) e
```java
        byte[] result = strategy.export(headers, new ArrayList<>());
```
por, respectivamente:
```java
        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);
```
e
```java
        byte[] result = strategy.export(headers, new ArrayList<>(), ExportMetadata.EMPTY);
```
(`ExportMetadata` está no mesmo pacote `com.devappmobile.flowfuel.export.strategy` — nenhum import novo necessário.)

- [ ] **Step 5: Atualizar `ExcelExportStrategy.export(...)` (ignora o parâmetro)**

Mesma mudança de assinatura do Step 3, em `ExcelExportStrategy.java`.

- [ ] **Step 6: Atualizar `ExcelExportStrategyTest` para a nova assinatura**

Em `ExcelExportStrategyTest.java`, trocar as duas chamadas:
```java
        byte[] result = strategy.export(headers, rows);
```
e
```java
        byte[] result = strategy.export(headers, List.of());
```
por:
```java
        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);
```
e
```java
        byte[] result = strategy.export(headers, List.of(), ExportMetadata.EMPTY);
```

- [ ] **Step 7: Atualizar `PdfExportStrategy.export(...)` (ainda ignora o parâmetro — Task 4 usa)**

Em `PdfExportStrategy.java`, trocar a assinatura:
```java
    public byte[] export(String[] headers, List<String[]> rows) {
```
por:
```java
    public byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata) {
```
(corpo do método inalterado por enquanto).

- [ ] **Step 8: Atualizar `PdfExportStrategyTest` para a nova assinatura**

Em `PdfExportStrategyTest.java`, trocar as duas chamadas:
```java
        byte[] result = strategy.export(headers, rows);
```
e
```java
        byte[] result = strategy.export(headers, List.of());
```
por:
```java
        byte[] result = strategy.export(headers, rows, ExportMetadata.EMPTY);
```
e
```java
        byte[] result = strategy.export(headers, List.of(), ExportMetadata.EMPTY);
```

- [ ] **Step 9: Run tests to verify everything compiles and passes**

Run: `./mvnw test -Dtest=CsvExportStrategyTest,ExcelExportStrategyTest,PdfExportStrategyTest`
Expected: PASS (todos os testes, comportamento inalterado)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/strategy/ExportMetadata.java \
  src/main/java/com/devappmobile/flowfuel/export/strategy/ExportStrategy.java \
  src/main/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategy.java \
  src/main/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategy.java \
  src/main/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategy.java \
  src/test/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategyTest.java \
  src/test/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategyTest.java \
  src/test/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategyTest.java
git commit -m "refactor(export): adicionar ExportMetadata à assinatura de ExportStrategy"
```

**Nota:** `ExportService` ainda chama `.export(headers, rows)` com 2 argumentos neste ponto — isso quebra a compilação de `ExportService.java` até o Task 5. Rode o Step 9 apenas com os testes de `strategy.*` (não a suíte completa) até lá; o Task 5 conserta `ExportService` e restaura o build completo.

---

### Task 4: `PdfExportStrategy` renderiza o metadata

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategy.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategyTest.java`

**Interfaces:**
- Consome `ExportMetadata` (Task 3). Nenhuma interface nova produzida.

- [ ] **Step 1: Write the failing test**

Adicionar em `PdfExportStrategyTest.java`:

```java
    @Test
    void export_comMetadataPreenchida_incluiTituloVeiculoPeriodoEResumoAntesDaTabela() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.of(new String[]{"25/06/2026", "MAINTENANCE", "150,00"});
        ExportMetadata metadata = new ExportMetadata(
                "Relatório de Eventos",
                "Toyota Corolla — ABC1D23",
                "01/01/2026 – 31/01/2026",
                List.of("Total gasto: R$ 150,00", "Eventos: 1", "MAINTENANCE: 1"));

        byte[] result = strategy.export(headers, rows, metadata);

        try (PdfReader reader = new PdfReader(result)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Relatório de Eventos");
            assertThat(text).contains("Toyota Corolla — ABC1D23");
            assertThat(text).contains("01/01/2026 – 31/01/2026");
            assertThat(text).contains("Total gasto: R$ 150,00");
            assertThat(text).contains("Eventos: 1");
            assertThat(text).contains("MAINTENANCE: 1");
            assertThat(text).contains("Data", "Tipo", "Valor", "150,00");
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PdfExportStrategyTest#export_comMetadataPreenchida_incluiTituloVeiculoPeriodoEResumoAntesDaTabela`
Expected: FAIL — o texto extraído não contém "Relatório de Eventos" nem as demais linhas (o metadata ainda é ignorado).

- [ ] **Step 3: Implementar a renderização do cabeçalho no `PdfExportStrategy`**

Substituir o conteúdo de `PdfExportStrategy.java` por:

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Component
public class PdfExportStrategy implements ExportStrategy {

    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 16, Font.BOLD, Color.BLACK);
    private static final Font LABEL_FONT = new Font(Font.HELVETICA, 11, Font.NORMAL, Color.DARK_GRAY);
    private static final Font SUMMARY_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, Color.BLACK);
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
    private static final Font BODY_FONT = new Font(Font.HELVETICA, 9);
    private static final Color HEADER_BACKGROUND = new Color(51, 102, 51);

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.PDF;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows, ExportMetadata metadata) {
        Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            addReportHeader(document, metadata);

            PdfPTable table = new PdfPTable(headers.length);
            table.setWidthPercentage(100);

            for (String header : headers) {
                table.addCell(headerCell(header));
            }
            for (String[] row : rows) {
                for (String field : row) {
                    table.addCell(bodyCell(field));
                }
            }

            document.add(table);
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar PDF", e);
        } finally {
            document.close();
        }

        return out.toByteArray();
    }

    private void addReportHeader(Document document, ExportMetadata metadata) throws DocumentException {
        if (metadata.reportTitle().isEmpty()) {
            return;
        }

        document.add(new Paragraph(metadata.reportTitle(), TITLE_FONT));
        document.add(new Paragraph(metadata.vehicleLabel(), LABEL_FONT));
        document.add(new Paragraph(metadata.periodLabel(), LABEL_FONT));
        document.add(new Paragraph(" "));

        for (String line : metadata.summaryLines()) {
            document.add(new Paragraph(line, SUMMARY_FONT));
        }

        document.add(new Paragraph(" "));
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setBackgroundColor(HEADER_BACKGROUND);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6);
        return cell;
    }

    private PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", BODY_FONT));
        cell.setPadding(5);
        return cell;
    }
}
```

`addReportHeader` pula o cabeçalho quando `reportTitle` está vazio (caso de `ExportMetadata.EMPTY`, usado pelos testes do Task 3) — mantém `export_semLinhas_geraApenasCabecalho` passando sem mudança nesse teste.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PdfExportStrategyTest`
Expected: PASS (todos os testes da classe, incluindo os 2 herdados do Task 3 e o novo)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategy.java \
  src/test/java/com/devappmobile/flowfuel/export/strategy/PdfExportStrategyTest.java
git commit -m "feat(export): PdfExportStrategy renderiza título, veículo, período e resumo"
```

---

### Task 5: `ExportService` monta o `ExportMetadata`

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/export/ExportService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java`

**Interfaces:**
- Consome `ExportMetadata` (Task 3), `RefuelConsumptionCalculator` (Task 2).
- Restaura a compilação de `ExportService.java` (quebrada desde o Task 3, que mudou a assinatura de `ExportStrategy.export`).

- [ ] **Step 1: Write the failing tests**

Adicionar em `ExportServiceTest.java` (a classe já importa `EnergyType`? não — adicionar `import com.devappmobile.flowfuel.vehicle.EnergyType;` junto dos imports existentes, e `import com.lowagie.text.pdf.PdfReader;` / `import com.lowagie.text.pdf.parser.PdfTextExtractor;`):

```java
    @Test
    void exportRefuels_formatoPdf_incluiCabecalhoDeVeiculoEResumoNoConteudo() throws Exception {
        vehicle.setLicensePlate("ABC1D23");
        when(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(List.of(
                buildRefuel(LocalDateTime.of(2026, 6, 1, 10, 0), 1500, 40.0, 5.0)
        ));

        ExportResult result = exportService.exportRefuels(owner, 10L, null, null, "pdf");

        try (PdfReader reader = new PdfReader(result.content())) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Relatório de Abastecimentos");
            assertThat(text).contains("Toyota Corolla — ABC1D23");
            assertThat(text).contains("Todo o histórico");
            assertThat(text).contains("Total gasto: R$ 200,00");
            assertThat(text).contains("Abastecimentos: 1");
        }
    }

    @Test
    void exportEvents_formatoPdf_incluiResumoPorCategoria() throws Exception {
        when(vehicleEventRepository.findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(
                        buildEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 6, 1), 150.0, "Revisão"),
                        buildEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 5, 1), 50.0, "Troca de óleo"),
                        buildEvent(VehicleEventType.CAR_WASH, LocalDate.of(2026, 4, 1), 40.0, "Lavagem")
                ));

        ExportResult result = exportService.exportEvents(owner, 10L, null, null, null, "pdf");

        try (PdfReader reader = new PdfReader(result.content())) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Relatório de Eventos");
            assertThat(text).contains("Total gasto: R$ 240,00");
            assertThat(text).contains("Eventos: 3");
            assertThat(text).contains("MAINTENANCE: 2");
            assertThat(text).contains("CAR_WASH: 1");
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail (compile error esperado)**

Run: `./mvnw test -Dtest=ExportServiceTest`
Expected: FAIL — erro de compilação, `ExportService.exportRefuels`/`exportEvents` ainda chamam `strategiesByFormat.get(format).export(headers, rows)` com 2 argumentos, incompatível com a interface atualizada no Task 3.

- [ ] **Step 3: Implementar `ExportService`**

Em `ExportService.java`:

Adicionar imports:
```java
import com.devappmobile.flowfuel.export.strategy.ExportMetadata;
import com.devappmobile.flowfuel.refuel.RefuelConsumptionCalculator;
import java.util.ArrayList;
```

Trocar o corpo de `exportRefuels` (a partir da linha `List<String[]> rows = refuels.stream().map(this::toRow).toList();`):

```java
        List<String[]> rows = refuels.stream().map(this::toRow).toList();
        ExportMetadata metadata = format == ExportFormat.PDF
                ? new ExportMetadata("Relatório de Abastecimentos", vehicleLabel(vehicle),
                        periodLabel(startDate, endDate), buildRefuelSummaryLines(refuels, vehicle))
                : ExportMetadata.EMPTY;
        byte[] content = strategiesByFormat.get(format).export(REFUEL_HEADERS, rows, metadata);
        String fileName = ExportFileNameBuilder.build("refuels", vehicle, format);

        return new ExportResult(content, fileName, contentTypeFor(format));
```

Trocar o corpo de `exportEvents` (a partir da linha `List<String[]> rows = events.stream().map(this::toRow).toList();`):

```java
        List<String[]> rows = events.stream().map(this::toRow).toList();
        ExportMetadata metadata = format == ExportFormat.PDF
                ? new ExportMetadata("Relatório de Eventos", vehicleLabel(vehicle),
                        periodLabel(startDate, endDate), buildEventSummaryLines(events))
                : ExportMetadata.EMPTY;
        byte[] content = strategiesByFormat.get(format).export(EVENT_HEADERS, rows, metadata);
        String fileName = ExportFileNameBuilder.build("events", vehicle, format);

        return new ExportResult(content, fileName, contentTypeFor(format));
```

Adicionar os métodos privados novos (junto dos demais métodos privados da classe, por exemplo logo antes de `findOwnedVehicle`):

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

    private List<String> buildRefuelSummaryLines(List<Refuel> refuels, Vehicle vehicle) {
        BigDecimal totalSpent = refuels.stream()
                .map(Refuel::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEnergy = refuels.stream()
                .map(Refuel::getEnergyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Refuel> fullTankDesc = refuels.stream().filter(Refuel::getFullTank).toList();
        Double averageConsumption = RefuelConsumptionCalculator.calculateAverageConsumption(fullTankDesc);

        return List.of(
                "Total gasto: R$ " + formatDecimal(totalSpent),
                "Total abastecido: " + formatDecimal(totalEnergy) + " " + vehicle.getEnergyUnit(),
                "Consumo médio: " + (averageConsumption > 0 ? formatDecimal(averageConsumption) : "-")
                        + " " + vehicle.getConsumptionUnit(),
                "Abastecimentos: " + refuels.size()
        );
    }

    private List<String> buildEventSummaryLines(List<VehicleEvent> events) {
        BigDecimal totalSpent = events.stream()
                .map(VehicleEvent::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<VehicleEventType, Long> countByType = events.stream()
                .collect(Collectors.groupingBy(VehicleEvent::getType, Collectors.counting()));

        List<String> lines = new ArrayList<>();
        lines.add("Total gasto: R$ " + formatDecimal(totalSpent));
        lines.add("Eventos: " + events.size());
        countByType.entrySet().stream()
                .sorted(Map.Entry.<VehicleEventType, Long>comparingByValue().reversed())
                .forEach(entry -> lines.add(entry.getKey().name() + ": " + entry.getValue()));
        return lines;
    }

    private String formatDecimal(BigDecimal value) {
        return formatDecimal(value.doubleValue());
    }

    private String formatDecimal(double value) {
        return "%.2f".formatted(value).replace('.', ',');
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ExportServiceTest`
Expected: PASS (todos os testes, incluindo os 2 novos)

- [ ] **Step 5: Rodar a suíte completa de export para checar regressões**

Run: `./mvnw test -Dtest="com.devappmobile.flowfuel.export.**"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportService.java \
  src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java
git commit -m "feat(export): ExportService monta ExportMetadata (veículo, período, resumo) para o PDF"
```

---

### Task 6: Teste de integração — `Content-Type` do PDF na resposta HTTP

**Files:**
- Modify: `src/test/java/com/devappmobile/flowfuel/export/ExportControllerIntegrationTest.java`

**Interfaces:** nenhuma nova — só cobertura de integração.

- [ ] **Step 1: Write the failing test**

Adicionar imports no topo de `ExportControllerIntegrationTest.java`:
```java
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import static org.assertj.core.api.Assertions.assertThat;
```

Adicionar o teste (junto dos demais `@Test` de sucesso, por exemplo logo após `exportEvents_xlsx_retornaArquivoComContentTypeCorreto`):

```java
    @Test
    void exportRefuels_pdf_retornaRelatorioComContentTypeEResumo() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "pdf")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("flowfuel-refuels-toyota-corolla-")))
                .andReturn();

        byte[] content = result.getResponse().getContentAsByteArray();
        try (PdfReader reader = new PdfReader(content)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Relatório de Abastecimentos");
            assertThat(text).contains("Toyota Corolla");
            assertThat(text).contains("Total gasto: R$ 235,60");
        }
    }
```

(o abastecimento criado em `setUp()` tem `energyAmount=40`, `pricePerUnit=5.89` → total `235.60`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ExportControllerIntegrationTest#exportRefuels_pdf_retornaRelatorioComContentTypeEResumo`
Expected: se os Tasks 1-5 já foram aplicados, este teste já deve passar de primeira (nada de produção muda neste task) — rode mesmo assim para confirmar; se falhar, revise se algum task anterior ficou incompleto antes de prosseguir.

- [ ] **Step 3: Run the full integration test class**

Run: `./mvnw test -Dtest=ExportControllerIntegrationTest`
Expected: PASS (todos os testes da classe)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/devappmobile/flowfuel/export/ExportControllerIntegrationTest.java
git commit -m "test(export): cobrir Content-Type e conteúdo do relatório PDF via MockMvc"
```

---

### Task 7: Verificação final

**Files:** nenhum (apenas execução)

- [ ] **Step 1: Rodar a suíte completa**

Run: `./mvnw test`
Expected: BUILD SUCCESS, nenhuma regressão em outros módulos (dashboard, refuel, vehicleevent, etc.)

- [ ] **Step 2: Build completo**

Run: `./mvnw package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verificação manual (não automatizável)**

Com o backend rodando localmente, chamar `GET /api/v1/exports/refuels?vehicleId={id}&format=pdf` com um token válido (via curl ou Postman) e abrir o PDF resultante — confirmar visualmente título, veículo, período, resumo e tabela. Repetir para `/exports/events`.
