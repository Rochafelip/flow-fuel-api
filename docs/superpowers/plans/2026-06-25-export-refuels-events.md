# Exportação de Abastecimentos e Eventos (CSV/XLSX) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/exports/refuels` and `GET /api/v1/exports/events`, letting an authenticated user download their vehicle's refuels or events as CSV or XLSX, with optional date-range (and, for events, type) filters.

**Architecture:** New `export` package implementing the Strategy pattern: an `ExportStrategy` interface with `CsvExportStrategy` and `ExcelExportStrategy` beans, selected by `ExportService` based on the requested `ExportFormat`. `ExportService` reuses `VehicleRepository` + `AuthorizationHelper` for ownership checks (same pattern as `RefuelService`/`VehicleEventService`), fetches rows via repository, maps them to plain `String[]` rows, and delegates byte generation to the chosen strategy. `ExportController` wraps the bytes in a `ResponseEntity` with `Content-Disposition`.

**Tech Stack:** Spring Boot 3.5.7, Java 21, Apache POI (`poi-ooxml`) for XLSX, plain `String` building for CSV (no extra dependency needed for CSV).

**Reference spec:** `docs/superpowers/specs/2026-06-25-export-refuels-events-design.md`

---

## Design recap (for context while implementing)

- Endpoints (final paths include the `/api/v1` prefix auto-applied by `config/WebMvcConfig.java`):
  - `GET /api/v1/exports/refuels?vehicleId=&startDate=&endDate=&format=csv|xlsx`
  - `GET /api/v1/exports/events?vehicleId=&type=&startDate=&endDate=&format=csv|xlsx`
- `vehicleId` required, `format` required (`csv`/`xlsx`, case-insensitive). `startDate`/`endDate` optional but must both be present or both absent, and `startDate <= endDate`.
- Refuels columns: Data, Combustível, Litros/kWh, Preço/unidade, Total, Odômetro.
- Events columns: Data, Tipo, Descrição, Valor, Odômetro.
- Filename: `flowfuel-{resource}-{slug-do-veiculo}-{ano-atual}.{ext}` (e.g. `flowfuel-refuels-corolla-2026.csv`). Slug from `Vehicle.brand` + `Vehicle.model` (kebab-case, lowercase, no accents), falling back to `veiculo-{id}` if both are blank.
- Errors reuse existing `ResourceNotFoundException`, `ForbiddenOperationException`, and a `BusinessRuleException`-style 400 for format/date validation (see Task 8).

---

### Task 1: Add Apache POI dependency

**Files:**
- Modify: `pom.xml:162-163` (just before `</dependencies>`)

- [ ] **Step 1: Add the dependency**

Insert right before the closing `</dependencies>` tag (after the `junit-jupiter` test dependency block, line 162):

```xml
		<dependency>
			<groupId>org.apache.poi</groupId>
			<artifactId>poi-ooxml</artifactId>
			<version>5.3.0</version>
		</dependency>
```

- [ ] **Step 2: Verify the project still resolves dependencies**

Run: `./mvnw -q dependency:resolve`
Expected: no errors, `poi-ooxml` and its transitive deps (e.g. `poi`, `poi-ooxml-lite`, `xmlbeans`) download successfully.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add Apache POI dependency for XLSX export"
```

---

### Task 2: ExportFormat enum

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/ExportFormat.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/ExportFormatTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportFormatTest {

    @Test
    void fromString_csvEmQualquerCaixa_retornaCSV() {
        assertThat(ExportFormat.fromString("csv")).isEqualTo(ExportFormat.CSV);
        assertThat(ExportFormat.fromString("CSV")).isEqualTo(ExportFormat.CSV);
        assertThat(ExportFormat.fromString("Csv")).isEqualTo(ExportFormat.CSV);
    }

    @Test
    void fromString_xlsxEmQualquerCaixa_retornaXLSX() {
        assertThat(ExportFormat.fromString("xlsx")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.fromString("XLSX")).isEqualTo(ExportFormat.XLSX);
    }

    @Test
    void fromString_valorInvalido_lancaIllegalArgumentException() {
        assertThatThrownBy(() -> ExportFormat.fromString("pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromString_nulo_lancaIllegalArgumentException() {
        assertThatThrownBy(() -> ExportFormat.fromString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExportFormatTest`
Expected: FAIL — compilation error, `ExportFormat` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.devappmobile.flowfuel.export;

public enum ExportFormat {
    CSV,
    XLSX;

    public static ExportFormat fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Formato de exportação não informado");
        }
        try {
            return ExportFormat.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Formato de exportação inválido: " + value);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExportFormatTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportFormat.java \
        src/test/java/com/devappmobile/flowfuel/export/ExportFormatTest.java
git commit -m "feat: add ExportFormat enum for csv/xlsx export requests"
```

---

### Task 3: ExportStrategy interface + CsvExportStrategy

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/strategy/ExportStrategy.java`
- Create: `src/main/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategy.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportStrategyTest {

    private final CsvExportStrategy strategy = new CsvExportStrategy();

    @Test
    void supportedFormat_retornaCSV() {
        assertThat(strategy.supportedFormat()).isEqualTo(ExportFormat.CSV);
    }

    @Test
    void export_comCabecalhoELinhas_geraCsvComVirgulaComoSeparador() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.of(
                new String[]{"25/06/2026", "MAINTENANCE", "150,00"},
                new String[]{"01/07/2026", "CAR_WASH", "40,00"}
        );

        byte[] result = strategy.export(headers, rows);
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly(
                "Data,Tipo,Valor",
                "25/06/2026,MAINTENANCE,150,00",
                "01/07/2026,CAR_WASH,40,00"
        );
    }

    @Test
    void export_comCampoContendoVirgula_envolveEmAspas() throws IOException {
        String[] headers = {"Data", "Descrição"};
        List<String[]> rows = List.of(new String[]{"25/06/2026", "Troca de óleo, filtro e correia"});

        byte[] result = strategy.export(headers, rows);
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly(
                "Data,Descrição",
                "25/06/2026,\"Troca de óleo, filtro e correia\""
        );
    }

    @Test
    void export_semLinhas_geraApenasCabecalho() throws IOException {
        String[] headers = {"Data", "Tipo"};

        byte[] result = strategy.export(headers, List.of());
        List<String> lines = readLines(result);

        assertThat(lines).containsExactly("Data,Tipo");
    }

    private List<String> readLines(byte[] content) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=CsvExportStrategyTest`
Expected: FAIL — `ExportStrategy`/`CsvExportStrategy` do not exist.

- [ ] **Step 3: Write the interface**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;

import java.util.List;

public interface ExportStrategy {

    ExportFormat supportedFormat();

    byte[] export(String[] headers, List<String[]> rows);
}
```

- [ ] **Step 4: Write the CSV implementation**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CsvExportStrategy implements ExportStrategy {

    private static final String SEPARATOR = ",";
    private static final String LINE_BREAK = "\n";

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.CSV;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(toCsvLine(headers));

        for (String[] row : rows) {
            csv.append(LINE_BREAK).append(toCsvLine(row));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String toCsvLine(String[] fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(SEPARATOR);
            }
            line.append(escape(fields[i]));
        }
        return line.toString();
    }

    private String escape(String field) {
        if (field == null) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=CsvExportStrategyTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/strategy/ExportStrategy.java \
        src/main/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategy.java \
        src/test/java/com/devappmobile/flowfuel/export/strategy/CsvExportStrategyTest.java
git commit -m "feat: add CsvExportStrategy"
```

---

### Task 4: ExcelExportStrategy (Apache POI)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategy.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelExportStrategyTest {

    private final ExcelExportStrategy strategy = new ExcelExportStrategy();

    @Test
    void supportedFormat_retornaXLSX() {
        assertThat(strategy.supportedFormat()).isEqualTo(ExportFormat.XLSX);
    }

    @Test
    void export_comCabecalhoELinhas_geraPlanilhaComConteudoCorreto() throws IOException {
        String[] headers = {"Data", "Tipo", "Valor"};
        List<String[]> rows = List.of(
                new String[]{"25/06/2026", "MAINTENANCE", "150,00"},
                new String[]{"01/07/2026", "CAR_WASH", "40,00"}
        );

        byte[] result = strategy.export(headers, rows);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            XSSFSheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            assertThat(cellValue(headerRow, 0)).isEqualTo("Data");
            assertThat(cellValue(headerRow, 1)).isEqualTo("Tipo");
            assertThat(cellValue(headerRow, 2)).isEqualTo("Valor");
            assertThat(headerRow.getCell(0).getCellStyle().getFont().getBold()).isTrue();

            Row firstDataRow = sheet.getRow(1);
            assertThat(cellValue(firstDataRow, 0)).isEqualTo("25/06/2026");
            assertThat(cellValue(firstDataRow, 1)).isEqualTo("MAINTENANCE");

            Row secondDataRow = sheet.getRow(2);
            assertThat(cellValue(secondDataRow, 2)).isEqualTo("40,00");

            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitTopRow()).isEqualTo((short) 1);

            assertThat(sheet.getCTWorksheet().getAutoFilter()).isNotNull();
        }
    }

    @Test
    void export_semLinhas_geraApenasCabecalho() throws IOException {
        String[] headers = {"Data", "Tipo"};

        byte[] result = strategy.export(headers, List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0)).isNotNull();
            assertThat(sheet.getRow(1)).isNull();
        }
    }

    private String cellValue(Row row, int index) {
        Cell cell = row.getCell(index);
        return cell.getStringCellValue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExcelExportStrategyTest`
Expected: FAIL — `ExcelExportStrategy` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.devappmobile.flowfuel.export.strategy;

import com.devappmobile.flowfuel.export.ExportFormat;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Component
public class ExcelExportStrategy implements ExportStrategy {

    private static final String SHEET_NAME = "Dados";

    @Override
    public ExportFormat supportedFormat() {
        return ExportFormat.XLSX;
    }

    @Override
    public byte[] export(String[] headers, List<String[]> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(SHEET_NAME);

            writeHeaderRow(workbook, sheet, headers);
            writeDataRows(sheet, rows);
            applyColumnAutoSize(sheet, headers.length);
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar planilha XLSX", e);
        }
    }

    private void writeHeaderRow(XSSFWorkbook workbook, XSSFSheet sheet, String[] headers) {
        XSSFFont boldFont = workbook.createFont();
        boldFont.setBold(true);
        XSSFCellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);

        XSSFRow headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            var cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDataRows(XSSFSheet sheet, List<String[]> rows) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XSSFRow row = sheet.createRow(rowIndex + 1);
            String[] fields = rows.get(rowIndex);
            for (int colIndex = 0; colIndex < fields.length; colIndex++) {
                row.createCell(colIndex).setCellValue(fields[colIndex]);
            }
        }
    }

    private void applyColumnAutoSize(XSSFSheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExcelExportStrategyTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategy.java \
        src/test/java/com/devappmobile/flowfuel/export/strategy/ExcelExportStrategyTest.java
git commit -m "feat: add ExcelExportStrategy with header styling, freeze pane and autofilter"
```

---

### Task 5: ExportFileNameBuilder

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilder.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.export.util;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFileNameBuilderTest {

    @Test
    void build_comMarcaEModelo_geraNomeEmKebabCaseSemAcentos() {
        Vehicle vehicle = new Vehicle();
        vehicle.setBrand("Toyotá");
        vehicle.setModel("Corolla Híbrido");

        String fileName = ExportFileNameBuilder.build("refuels", vehicle, ExportFormat.CSV);

        assertThat(fileName).isEqualTo(
                "flowfuel-refuels-toyota-corolla-hibrido-" + Year.now().getValue() + ".csv");
    }

    @Test
    void build_comFormatoXlsx_usaExtensaoXlsx() {
        Vehicle vehicle = new Vehicle();
        vehicle.setBrand("Honda");
        vehicle.setModel("Civic");

        String fileName = ExportFileNameBuilder.build("events", vehicle, ExportFormat.XLSX);

        assertThat(fileName).isEqualTo(
                "flowfuel-events-honda-civic-" + Year.now().getValue() + ".xlsx");
    }

    @Test
    void build_semMarcaNemModelo_usaVeiculoMaisId() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(42L);

        String fileName = ExportFileNameBuilder.build("refuels", vehicle, ExportFormat.CSV);

        assertThat(fileName).isEqualTo(
                "flowfuel-refuels-veiculo-42-" + Year.now().getValue() + ".csv");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExportFileNameBuilderTest`
Expected: FAIL — `ExportFileNameBuilder` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.devappmobile.flowfuel.export.util;

import com.devappmobile.flowfuel.export.ExportFormat;
import com.devappmobile.flowfuel.vehicle.Vehicle;

import java.text.Normalizer;
import java.time.Year;
import java.util.regex.Pattern;

public final class ExportFileNameBuilder {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private ExportFileNameBuilder() {
    }

    public static String build(String resourceType, Vehicle vehicle, ExportFormat format) {
        String slug = vehicleSlug(vehicle);
        String extension = format == ExportFormat.XLSX ? "xlsx" : "csv";
        return "flowfuel-%s-%s-%d.%s".formatted(resourceType, slug, Year.now().getValue(), extension);
    }

    private static String vehicleSlug(Vehicle vehicle) {
        String brand = vehicle.getBrand() != null ? vehicle.getBrand() : "";
        String model = vehicle.getModel() != null ? vehicle.getModel() : "";
        String combined = (brand + " " + model).trim();

        if (combined.isEmpty()) {
            return "veiculo-" + vehicle.getId();
        }

        String normalized = Normalizer.normalize(combined, Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .toLowerCase();
        String slug = NON_ALPHANUMERIC.matcher(normalized).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExportFileNameBuilderTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilder.java \
        src/test/java/com/devappmobile/flowfuel/export/util/ExportFileNameBuilderTest.java
git commit -m "feat: add ExportFileNameBuilder for standardized export file names"
```

---

### Task 6: ExportValidationException (400 for format/date errors)

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/ExportValidationException.java`

This is a thin `AppException` subclass mapping to `ErrorCode.VALIDATION_FAILED`, reusing the existing `GlobalExceptionHandler.handleAppException` path — no new exception handler needed.

- [ ] **Step 1: Write the implementation (no separate test — covered by ExportService and controller integration tests in later tasks)**

```java
package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.error.AppException;
import com.devappmobile.flowfuel.common.error.ErrorCode;

public class ExportValidationException extends AppException {

    public ExportValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }
}
```

- [ ] **Step 2: Compile to verify it builds**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportValidationException.java
git commit -m "feat: add ExportValidationException mapped to VALIDATION_FAILED"
```

---

### Task 7: VehicleEventRepository — add non-paginated list query methods

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventRepository.java`
- Test: `src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventRepositoryListTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.devappmobile.flowfuel.vehicleevent;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class VehicleEventRepositoryListTest {

    @Autowired private VehicleEventRepository vehicleEventRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private UserRepository userRepository;

    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("test@test.com");
        user.setPassword("hash");
        user.setName("Test");
        user = userRepository.save(user);

        vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(0);
        vehicle.setCapacity(50);
        vehicle.setUser(user);
        vehicle = vehicleRepository.save(vehicle);

        saveEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 1, 10));
        saveEvent(VehicleEventType.CAR_WASH, LocalDate.of(2026, 3, 5));
        saveEvent(VehicleEventType.MAINTENANCE, LocalDate.of(2026, 6, 1));
    }

    private void saveEvent(VehicleEventType type, LocalDate date) {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(type);
        event.setAmount(BigDecimal.valueOf(100));
        event.setEventDate(date);
        vehicleEventRepository.save(event);
    }

    @Test
    void findByVehicleId_semFiltro_retornaTodosOrdenadosPorDataDesc() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(vehicle.getId());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.get(2).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void findByVehicleIdAndType_filtraPorTipo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), VehicleEventType.MAINTENANCE);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getType() == VehicleEventType.MAINTENANCE);
    }

    @Test
    void findByVehicleIdAndEventDateBetween_filtraPorPeriodo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 1));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(VehicleEventType.CAR_WASH);
    }

    @Test
    void findByVehicleIdAndTypeAndEventDateBetween_filtraPorTipoEPeriodo() {
        List<VehicleEvent> result = vehicleEventRepository
                .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                        vehicle.getId(), VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 12, 31));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=VehicleEventRepositoryListTest`
Expected: FAIL — compilation error, the four `List`-returning methods don't exist on `VehicleEventRepository`.

- [ ] **Step 3: Add the methods to the repository**

Modify `src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventRepository.java` — add these four method declarations inside the interface, after the existing `Page`-returning ones:

```java
    List<VehicleEvent> findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId);

    List<VehicleEvent> findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type);

    List<VehicleEvent> findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            LocalDate startDate,
            LocalDate endDate);

    List<VehicleEvent> findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
            Long vehicleId,
            VehicleEventType type,
            LocalDate startDate,
            LocalDate endDate);
```

Add `import java.util.List;` to the existing import block at the top of the file.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=VehicleEventRepositoryListTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventRepository.java \
        src/test/java/com/devappmobile/flowfuel/vehicleevent/VehicleEventRepositoryListTest.java
git commit -m "feat: add non-paginated list query methods to VehicleEventRepository"
```

---

### Task 8: ExportService — refuels export

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/ExportService.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java`

This task wires the refuels half of `ExportService`; Task 9 adds the events half to the same class/test file.

- [ ] **Step 1: Write the failing tests**

```java
package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ForbiddenOperationException;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.export.strategy.CsvExportStrategy;
import com.devappmobile.flowfuel.export.strategy.ExcelExportStrategy;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock private RefuelRepository refuelRepository;
    @Mock private VehicleEventRepository vehicleEventRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private AuthorizationHelper authorizationHelper;

    @Spy private CsvExportStrategy csvExportStrategy = new CsvExportStrategy();
    @Spy private ExcelExportStrategy excelExportStrategy = new ExcelExportStrategy();

    private ExportService exportService;

    private User owner;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(
                refuelRepository, vehicleEventRepository, vehicleRepository,
                authorizationHelper, List.of(csvExportStrategy, excelExportStrategy));

        owner = new User("owner@test.com", "hash", "Owner");
        owner.setId(1L);

        vehicle = new Vehicle();
        vehicle.setId(10L);
        vehicle.setUser(owner);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");

        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));
    }

    private Refuel buildRefuel(LocalDateTime date, int odometer, double energy, double price) {
        Refuel refuel = new Refuel();
        refuel.setRefuelDate(date);
        refuel.setOdometer(odometer);
        refuel.setEnergyAmount(BigDecimal.valueOf(energy));
        refuel.setPricePerUnit(BigDecimal.valueOf(price));
        refuel.setRefuelType(RefuelType.FUEL);
        refuel.calculateTotalAmount();
        refuel.setVehicle(vehicle);
        return refuel;
    }

    @Test
    void exportRefuels_semFiltroDeData_geraCsvComTodosOsAbastecimentos() {
        when(refuelRepository.findByVehicleIdOrderByRefuelDateDesc(10L)).thenReturn(List.of(
                buildRefuel(LocalDateTime.of(2026, 6, 1, 10, 0), 1500, 40.0, 5.89)
        ));

        ExportResult result = exportService.exportRefuels(owner, 10L, null, null, "csv");

        assertThat(result.fileName()).isEqualTo("flowfuel-refuels-toyota-corolla-" + java.time.Year.now().getValue() + ".csv");
        assertThat(result.contentType()).isEqualTo("text/csv");
        String content = new String(result.content());
        assertThat(content).contains("Data,Combustível,Litros/kWh,Preço/unidade,Total,Odômetro");
        assertThat(content).contains("01/06/2026");
    }

    @Test
    void exportRefuels_comPeriodo_usaQueryDeIntervalo() {
        when(refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                10L, LocalDate.of(2026, 1, 1).atStartOfDay(), LocalDate.of(2026, 12, 31).atTime(23, 59, 59)))
                .thenReturn(List.of(buildRefuel(LocalDateTime.of(2026, 3, 1, 8, 0), 1000, 35.0, 5.5)));

        ExportResult result = exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "xlsx");

        assertThat(result.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.fileName()).endsWith(".xlsx");
    }

    @Test
    void exportRefuels_vehicleInexistente_lancaResourceNotFoundException() {
        when(vehicleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exportService.exportRefuels(owner, 999L, null, null, "csv"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void exportRefuels_vehicleDeOutroUsuario_propagaForbiddenOperationException() {
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("não autorizado"))
                .when(authorizationHelper).ensureOwnsVehicle(owner, vehicle);

        assertThatThrownBy(() -> exportService.exportRefuels(owner, 10L, null, null, "csv"))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void exportRefuels_formatoInvalido_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(owner, 10L, null, null, "pdf"))
                .isInstanceOf(ExportValidationException.class);
    }

    @Test
    void exportRefuels_apenasStartDateInformada_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 1, 1), null, "csv"))
                .isInstanceOf(ExportValidationException.class);
    }

    @Test
    void exportRefuels_startDateDepoisDeEndDate_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportRefuels(
                owner, 10L, LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1), "csv"))
                .isInstanceOf(ExportValidationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExportServiceTest`
Expected: FAIL — `ExportService` and `ExportResult` don't exist yet.

- [ ] **Step 3: Write `ExportResult`**

Create `src/main/java/com/devappmobile/flowfuel/export/ExportResult.java`:

```java
package com.devappmobile.flowfuel.export;

public record ExportResult(byte[] content, String fileName, String contentType) {
}
```

- [ ] **Step 4: Write `ExportService` (refuels method only for now)**

Create `src/main/java/com/devappmobile/flowfuel/export/ExportService.java`:

```java
package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.common.AuthorizationHelper;
import com.devappmobile.flowfuel.exception.ResourceNotFoundException;
import com.devappmobile.flowfuel.export.strategy.ExportStrategy;
import com.devappmobile.flowfuel.export.util.ExportFileNameBuilder;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String[] REFUEL_HEADERS =
            {"Data", "Combustível", "Litros/kWh", "Preço/unidade", "Total", "Odômetro"};

    private final RefuelRepository refuelRepository;
    private final VehicleEventRepository vehicleEventRepository;
    private final VehicleRepository vehicleRepository;
    private final AuthorizationHelper authorizationHelper;
    private final Map<ExportFormat, ExportStrategy> strategiesByFormat;

    public ExportService(RefuelRepository refuelRepository,
            VehicleEventRepository vehicleEventRepository,
            VehicleRepository vehicleRepository,
            AuthorizationHelper authorizationHelper,
            List<ExportStrategy> strategies) {
        this.refuelRepository = refuelRepository;
        this.vehicleEventRepository = vehicleEventRepository;
        this.vehicleRepository = vehicleRepository;
        this.authorizationHelper = authorizationHelper;
        this.strategiesByFormat = strategies.stream()
                .collect(Collectors.toMap(ExportStrategy::supportedFormat, Function.identity()));
    }

    public ExportResult exportRefuels(User user, Long vehicleId, LocalDate startDate, LocalDate endDate,
            String formatParam) {
        ExportFormat format = ExportFormat.fromString(formatParam);
        validateDateRange(startDate, endDate);

        Vehicle vehicle = findOwnedVehicle(user, vehicleId);

        List<Refuel> refuels;
        if (startDate != null && endDate != null) {
            refuels = refuelRepository.findByVehicleIdAndRefuelDateBetweenOrderByRefuelDateDesc(
                    vehicleId, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
        } else {
            refuels = refuelRepository.findByVehicleIdOrderByRefuelDateDesc(vehicleId);
        }

        List<String[]> rows = refuels.stream().map(this::toRow).toList();
        byte[] content = strategiesByFormat.get(format).export(REFUEL_HEADERS, rows);
        String fileName = ExportFileNameBuilder.build("refuels", vehicle, format);

        return new ExportResult(content, fileName, contentTypeFor(format));
    }

    private String[] toRow(Refuel refuel) {
        return new String[]{
                refuel.getRefuelDate().format(DATE_FORMAT),
                refuel.getRefuelType().name(),
                refuel.getEnergyAmount().toPlainString(),
                refuel.getPricePerUnit().toPlainString(),
                refuel.getTotalAmount().toPlainString(),
                String.valueOf(refuel.getOdometer())
        };
    }

    private Vehicle findOwnedVehicle(User user, Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Veículo", vehicleId));
        authorizationHelper.ensureOwnsVehicle(user, vehicle);
        return vehicle;
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new ExportValidationException("startDate e endDate devem ser informados juntos");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ExportValidationException("startDate não pode ser depois de endDate");
        }
    }

    private String contentTypeFor(ExportFormat format) {
        return format == ExportFormat.XLSX
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv";
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExportServiceTest`
Expected: PASS (7 tests) — note `vehicleEventRepository` is unused for now, that's expected until Task 9.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportService.java \
        src/main/java/com/devappmobile/flowfuel/export/ExportResult.java \
        src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java
git commit -m "feat: add ExportService.exportRefuels with CSV/XLSX strategy selection"
```

---

### Task 9: ExportService — events export

**Files:**
- Modify: `src/main/java/com/devappmobile/flowfuel/export/ExportService.java`
- Modify: `src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java`

- [ ] **Step 1: Add failing tests to `ExportServiceTest`**

Add these imports to the existing test file:

```java
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
```

Add this helper method and these test methods inside the `ExportServiceTest` class:

```java
    private VehicleEvent buildEvent(VehicleEventType type, LocalDate date, double amount, String description) {
        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(type);
        event.setEventDate(date);
        event.setAmount(BigDecimal.valueOf(amount));
        event.setDescription(description);
        event.setOdometer(2000);
        return event;
    }

    @Test
    void exportEvents_semFiltro_geraCsvComTodosOsEventos() {
        when(vehicleEventRepository.findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(10L))
                .thenReturn(List.of(buildEvent(VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 6, 1), 150.0, "Troca de óleo")));

        ExportResult result = exportService.exportEvents(owner, 10L, null, null, null, "csv");

        String content = new String(result.content());
        assertThat(content).contains("Data,Tipo,Descrição,Valor,Odômetro");
        assertThat(content).contains("01/06/2026,MAINTENANCE,Troca de óleo,150,2000");
    }

    @Test
    void exportEvents_comTipo_usaQueryFiltradaPorTipo() {
        when(vehicleEventRepository.findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(
                10L, VehicleEventType.CAR_WASH))
                .thenReturn(List.of(buildEvent(VehicleEventType.CAR_WASH,
                        LocalDate.of(2026, 5, 1), 40.0, "Lavagem completa")));

        ExportResult result = exportService.exportEvents(
                owner, 10L, VehicleEventType.CAR_WASH, null, null, "csv");

        assertThat(new String(result.content())).contains("CAR_WASH");
    }

    @Test
    void exportEvents_comTipoEPeriodo_usaQueryFiltradaPorTipoEPeriodo() {
        when(vehicleEventRepository.findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                10L, VehicleEventType.MAINTENANCE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(buildEvent(VehicleEventType.MAINTENANCE,
                        LocalDate.of(2026, 6, 1), 150.0, "Revisão")));

        ExportResult result = exportService.exportEvents(owner, 10L, VehicleEventType.MAINTENANCE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "xlsx");

        assertThat(result.fileName()).endsWith(".xlsx");
    }

    @Test
    void exportEvents_apenasEndDateInformada_lancaExportValidationException() {
        assertThatThrownBy(() -> exportService.exportEvents(
                owner, 10L, null, null, LocalDate.of(2026, 1, 1), "csv"))
                .isInstanceOf(ExportValidationException.class);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExportServiceTest`
Expected: FAIL — `exportEvents` method doesn't exist on `ExportService`.

- [ ] **Step 3: Add `exportEvents` to `ExportService`**

Add to the imports of `ExportService.java`:

```java
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
```

Add this constant next to `REFUEL_HEADERS`:

```java
    private static final String[] EVENT_HEADERS =
            {"Data", "Tipo", "Descrição", "Valor", "Odômetro"};
```

Add this method to the class:

```java
    public ExportResult exportEvents(User user, Long vehicleId, VehicleEventType type,
            LocalDate startDate, LocalDate endDate, String formatParam) {
        ExportFormat format = ExportFormat.fromString(formatParam);
        validateDateRange(startDate, endDate);

        Vehicle vehicle = findOwnedVehicle(user, vehicleId);

        List<VehicleEvent> events;
        if (type != null && startDate != null && endDate != null) {
            events = vehicleEventRepository
                    .findByVehicleIdAndTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, type, startDate, endDate);
        } else if (type != null) {
            events = vehicleEventRepository
                    .findByVehicleIdAndTypeOrderByEventDateDescCreatedAtDescIdDesc(vehicleId, type);
        } else if (startDate != null && endDate != null) {
            events = vehicleEventRepository
                    .findByVehicleIdAndEventDateBetweenOrderByEventDateDescCreatedAtDescIdDesc(
                            vehicleId, startDate, endDate);
        } else {
            events = vehicleEventRepository
                    .findByVehicleIdOrderByEventDateDescCreatedAtDescIdDesc(vehicleId);
        }

        List<String[]> rows = events.stream().map(this::toRow).toList();
        byte[] content = strategiesByFormat.get(format).export(EVENT_HEADERS, rows);
        String fileName = ExportFileNameBuilder.build("events", vehicle, format);

        return new ExportResult(content, fileName, contentTypeFor(format));
    }

    private String[] toRow(VehicleEvent event) {
        return new String[]{
                event.getEventDate().format(DATE_FORMAT),
                event.getType().name(),
                event.getDescription() != null ? event.getDescription() : "",
                event.getAmount().toPlainString(),
                event.getOdometer() != null ? String.valueOf(event.getOdometer()) : ""
        };
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExportServiceTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportService.java \
        src/test/java/com/devappmobile/flowfuel/export/ExportServiceTest.java
git commit -m "feat: add ExportService.exportEvents with type and date filters"
```

---

### Task 10: ExportController

**Files:**
- Create: `src/main/java/com/devappmobile/flowfuel/export/ExportController.java`
- Test: `src/test/java/com/devappmobile/flowfuel/export/ExportControllerIntegrationTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.refuel.RefuelType;
import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.user.UserRepository;
import com.devappmobile.flowfuel.user.UserStatus;
import com.devappmobile.flowfuel.vehicle.EnergyType;
import com.devappmobile.flowfuel.vehicle.Vehicle;
import com.devappmobile.flowfuel.vehicle.VehicleRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEvent;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventRepository;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import com.devappmobile.flowfuel.refuel.Refuel;
import com.devappmobile.flowfuel.refuel.RefuelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ExportControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private RefuelRepository refuelRepository;
    @Autowired private VehicleEventRepository vehicleEventRepository;
    @Autowired private ObjectMapper objectMapper;

    private Vehicle vehicle;
    private String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        vehicleEventRepository.deleteAll();
        refuelRepository.deleteAll();
        vehicleRepository.deleteAll();
        userRepository.deleteAll();

        ownerToken = registerAndLogin("owner@test.com");

        User owner = userRepository.findByEmail("owner@test.com").orElseThrow();
        vehicle = new Vehicle();
        vehicle.setType("car");
        vehicle.setEnergyType(EnergyType.COMBUSTION);
        vehicle.setCurrentKm(1500);
        vehicle.setCapacity(50);
        vehicle.setBrand("Toyota");
        vehicle.setModel("Corolla");
        vehicle.setUser(owner);
        vehicle = vehicleRepository.save(vehicle);

        Refuel refuel = new Refuel();
        refuel.setVehicle(vehicle);
        refuel.setOdometer(1500);
        refuel.setEnergyAmount(BigDecimal.valueOf(40));
        refuel.setPricePerUnit(BigDecimal.valueOf(5.89));
        refuel.setRefuelType(RefuelType.FUEL);
        refuel.setRefuelDate(LocalDateTime.of(2026, 6, 1, 10, 0));
        refuelRepository.save(refuel);

        VehicleEvent event = new VehicleEvent();
        event.setVehicle(vehicle);
        event.setType(VehicleEventType.MAINTENANCE);
        event.setAmount(BigDecimal.valueOf(150));
        event.setEventDate(LocalDate.of(2026, 6, 1));
        vehicleEventRepository.save(event);
    }

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123","name":"User"}
                        """.formatted(email)));
        userRepository.findByEmail(email).ifPresent(u -> {
            u.setStatus(UserStatus.ACTIVE);
            userRepository.save(u);
        });
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"senha123"}
                        """.formatted(email)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void exportRefuels_csv_retornaArquivoComHeadersCorretos() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("flowfuel-refuels-toyota-corolla-")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("01/06/2026")));
    }

    @Test
    void exportEvents_xlsx_retornaArquivoComContentTypeCorreto() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "xlsx")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void exportRefuels_veiculoDeOutroUsuario_retorna403() throws Exception {
        String otherToken = registerAndLogin("other@test.com");

        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportRefuels_veiculoInexistente_retorna404() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", "999999")
                .param("format", "csv")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportRefuels_formatoInvalido_retorna400() throws Exception {
        mockMvc.perform(get("/api/v1/exports/refuels")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "pdf")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportEvents_datasInvertidas_retorna400() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv")
                .param("startDate", "2026-12-31")
                .param("endDate", "2026-01-01")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportEvents_semAutenticacao_retorna401() throws Exception {
        mockMvc.perform(get("/api/v1/exports/events")
                .param("vehicleId", vehicle.getId().toString())
                .param("format", "csv"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=ExportControllerIntegrationTest`
Expected: FAIL — `ExportController` does not exist (404/no mapping).

- [ ] **Step 3: Write the controller**

```java
package com.devappmobile.flowfuel.export;

import com.devappmobile.flowfuel.user.User;
import com.devappmobile.flowfuel.vehicleevent.VehicleEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/exports")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    @GetMapping("/refuels")
    public ResponseEntity<byte[]> exportRefuels(
            @AuthenticationPrincipal User user,
            @RequestParam Long vehicleId,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam String format) {
        ExportResult result = exportService.exportRefuels(user, vehicleId, startDate, endDate, format);
        return toResponse(result);
    }

    @GetMapping("/events")
    public ResponseEntity<byte[]> exportEvents(
            @AuthenticationPrincipal User user,
            @RequestParam Long vehicleId,
            @RequestParam(required = false) VehicleEventType type,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam String format) {
        ExportResult result = exportService.exportEvents(user, vehicleId, type, startDate, endDate, format);
        return toResponse(result);
    }

    private ResponseEntity<byte[]> toResponse(ExportResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(result.fileName()).build());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.content());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=ExportControllerIntegrationTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devappmobile/flowfuel/export/ExportController.java \
        src/test/java/com/devappmobile/flowfuel/export/ExportControllerIntegrationTest.java
git commit -m "feat: add GET /exports/refuels and /exports/events endpoints"
```

---

### Task 11: Full test suite + manual sanity check

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, all tests pass (including the new ones from Tasks 2–10).

- [ ] **Step 2: Manually verify XLSX output opens correctly**

Run: `./mvnw -q spring-boot:run` (in one terminal), then in another:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<an existing active user email>","password":"<password>"}' | jq -r .accessToken)

curl -s "localhost:8080/api/v1/exports/refuels?vehicleId=<id>&format=xlsx" \
  -H "Authorization: Bearer $TOKEN" -o /tmp/refuels.xlsx

file /tmp/refuels.xlsx
```

Expected: `file` reports `Microsoft Excel 2007+`. Open it (or `unzip -l /tmp/refuels.xlsx`) and confirm it contains a `xl/worksheets/sheet1.xml`.

Stop the running app afterward (Ctrl+C).

- [ ] **Step 3: Commit (only if Step 1 required fixes)**

If the full suite already passed with no changes, skip this step — there is nothing to commit.

---

## Self-review notes

- **Spec coverage:** Endpoints (5.1, 5.2) ✓ Task 10; CSV/XLSX strategies (5.9) ✓ Tasks 3–4; date filters (5.4) ✓ `validateDateRange` + tests in Task 8/9/10; smart file naming (5.12) ✓ Task 5; ownership/auth reuse ✓ Task 8 tests; error handling (404/403/400) ✓ Tasks 8–10.
- **Out of scope confirmed:** `allVehicles`/multi-vehicle export (5.5), full consolidated report (5.3), multi-sheet XLSX (5.10), financial/dashboard reports (5.6/5.7), unified endpoint (5.8), customizable report selection (5.11) — all deferred to Specs 2 and 3 per the design doc.
- **"Posto" column (5.1):** intentionally omitted, documented as `[INFERIDO]` in the spec — no task adds it.
