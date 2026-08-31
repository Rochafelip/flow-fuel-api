# Exportação CSV — BOM UTF-8 para Corrigir Acentuação

## Contexto

`CsvExportStrategy` (`docs/superpowers/specs/2026-06-25-export-refuels-events-design.md`)
já escreve o conteúdo do CSV em UTF-8 e com acentuação correta no
código-fonte (ex.: "Combustível", "Preço"), mas o arquivo é gravado sem BOM
(`CsvExportStrategy.java`, método `export`, linha `out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8))`).
Sem o BOM, o Excel (leitor mais comum no Windows/Android para arquivos
baixados) assume Latin-1/Windows-1252 ao abrir o arquivo e exibe os
caracteres acentuados corrompidos (ex.: "PreÃ§o" em vez de "Preço").

## Fix

`CsvExportStrategy.export()` passa a prefixar o array de bytes retornado com
o BOM UTF-8 (`0xEF 0xBB 0xBF`), antes do conteúdo do CSV já gerado. Nenhuma
outra mudança de comportamento — separador, escaping de campos e quebras de
linha continuam exatamente como estão hoje.

## Escopo

- Afeta apenas `CsvExportStrategy`, usado tanto por `/exports/refuels`
  quanto por `/exports/events` (mesma classe para os dois recursos).
- XLSX é um formato binário (ZIP + XML) — não é afetado por esse problema,
  nenhuma mudança em `ExcelExportStrategy`.
- PDF desenha texto diretamente no `Document`/`Canvas` do OpenPDF — a
  fonte já define o encoding correto na renderização, também não afetado.
- Nenhuma mudança no frontend: `downloadExport` (`flowfuel-frontend`) já
  baixa o blob exatamente como a resposta chega; o BOM é transparente para
  o navegador e para o Excel ao abrir o arquivo.

## Testes

`CsvExportStrategyTest` ganha um novo caso verificando que os 3 primeiros
bytes do resultado são o BOM UTF-8. Os testes existentes que já leem o
conteúdo como texto via o helper `readLines` (usa `InputStreamReader` com
`StandardCharsets.UTF_8`) precisam de ajuste: `InputStreamReader` **não**
descarta um BOM líder automaticamente — ele decodifica os 3 bytes como o
caractere `﻿`, que apareceria colado no início da primeira linha e
quebraria as asserções `containsExactly(...)` já existentes. O helper
`readLines` passa a remover esse `﻿` inicial, se presente, antes de
dividir em linhas — mudança isolada no helper de teste, nenhuma asserção
existente muda de texto esperado.
