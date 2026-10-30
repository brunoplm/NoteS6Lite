# NoteS6Lite

Aplicativo de anotações para o **Galaxy Tab S6 Lite**, focado em escrita com S-Pen
de baixa latência, rejeição de palma e separação rígida entre fundo e tinta.

## Recursos

### Biblioteca de pastas (tela inicial)
- Pastas **hierárquicas infinitas**: uma pasta pode conter subpastas e notas.
- Grade otimizada para tablet (4 colunas em telas ≥ 600 dp, 2 em celulares).
- **Migalhas de pão** clicáveis (`Início › Semestre 1 › Física`) para voltar a
  qualquer nível.
- Botões **Nova Pasta** e **Nova Nota** (a nota é criada na pasta atual e abre
  o editor).
- Segurar um item abre **Renomear / Mover para… / Excluir**.
- Mover nunca cria ciclos (uma pasta não pode ir para dentro da própria
  subárvore).
- Excluir uma pasta remove suas subpastas, as notas dentro delas e os PNGs de
  PDF dessas notas.

### Escrita
- Renderização direta em `android.graphics.Canvas` (sem WebView, sem Compose).
- Traço suavizado com curvas quadráticas de Bézier.
- Uso de `MotionEvent.getHistoricalX/Y` no `ACTION_MOVE` para aproveitar todos os
  pontos do digitalizador (zero lag perceptível).
- **Largura variável real** por pressão da S-Pen (`event.getPressure`), com
  resposta não linear (`sqrt`) e suavização exponencial.
- Contorno preenchido (um `Path` por traço) em vez de `strokeWidth` fixo, o que
  mantém 1 `drawPath` por traço mesmo com pressão.
- Caneta, marca-texto e borracha (pixel eraser).

### Rejeição de palma
- `TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` têm prioridade absoluta.
- `TOOL_TYPE_FINGER` só faz pan e zoom (inclusive pinça).
- Um dedo que sobe (`ACTION_POINTER_UP`) nunca encerra um traço ativo da S-Pen:
  a comparação é feita pelo `pointerId` do ponteiro que saiu.
- Durante a escrita, ponteiros extras são ignorados.

### Camadas isoladas
- O fundo (template, grade, pauta ou página de PDF) é desenhado pelo
  `SheetPainter` e **nunca** entra na camada de tinta.
- A borracha usa `PorterDuff.CLEAR` somente dentro de um `saveLayer` de tinta.
- O `saveLayer` só é criado quando a página possui traços de borracha (ou uma
  borracha está ativa), evitando custo por frame em páginas comuns.

### Páginas e folhas
- Templates: **A4**, **Apresentação 16:9** e **Canvas Infinito**.
- Estilos de folha: **Em branco**, **Pautada**, **Quadriculada** e **Dark Mode**.
- No Dark Mode a tinta escura é trocada automaticamente por tinta clara
  (exceto quando há uma página de PDF branca por cima).
- Pan/zoom por gesto de dois dedos, com "Ajustar Tela" para reenquadrar.

### Notas e persistência
- Múltiplas notas com criar, abrir, renomear, mover e excluir.
- Cada nota tem template/estilo padrão herdados por novas páginas.
- Navegação de páginas com criação automática da próxima página.
- Autosave com debounce de 700 ms após cada traço; troca de página e
  `onPause` forçam a gravação imediata.

### PDF
- **Importar**: renderiza cada página do PDF em PNG (`filesDir/pdf_pages`) fora
  da main thread e cria um caderno com essas páginas.
- **Exportar**: gera um PDF com fundo + tinta, em tamanho de página imprimível
  (595 pt de largura, proporcional à altura).
- A tinta é sempre exportada em uma camada transparente separada, então a
  borracha nunca apaga o fundo exportado.

## Arquitetura

```
app/src/main/java/com/notes6lite/app/
├── HomeActivity.kt              # biblioteca: pastas, notas, migalhas, mover
├── MainActivity.kt              # editor: UI, autosave, import/export PDF
├── NoteCanvasView.kt            # View de tinta (S-Pen, pan/zoom, camadas)
├── domain/model/                # Modelos puros (sem Android/Room)
│   ├── Folder.kt                # Folder + LibraryContent
│   ├── InkPoint.kt
│   ├── Stroke.kt
│   ├── Notebook.kt              # a "nota" (documento com páginas)
│   ├── NotePage.kt              # inclui PageSnapshot
│   ├── PageTemplate.kt
│   ├── SheetStyle.kt
│   └── ToolMode.kt
├── data/local/                  # Room
│   ├── NoteDatabase.kt          # versão 2 + migração 1 -> 2
│   ├── StrokeCodec.kt           # traços em BLOB binário little-endian
│   ├── entity/                  # inclui FolderEntity
│   └── dao/                     # inclui FolderDao
├── data/repository/
│   └── NotebookRepository.kt    # pastas, notas, páginas e traços
├── render/
│   ├── StrokeOutlineBuilder.kt  # contorno com largura por pressão
│   ├── InkPainter.kt            # caneta / marca-texto / borracha
│   └── SheetPainter.kt          # fundo: templates, pautas, grade, dark
└── ui/
    ├── HomeViewModel.kt         # conteúdo da pasta (Flow) + mover/excluir
    └── NotesViewModel.kt        # corrotinas + Mutex de escrita
```

### Banco de dados (Room, versão 2)
- `folders` (auto-referenciada por `parentFolderId`, `ON DELETE CASCADE`) →
  subpastas.
- `notebooks.folderId` (nulo = raiz) liga a nota à pasta. A exclusão em
  cascata das notas é feita no repositório, em transação.
- `notebooks` → `pages` (`ON DELETE CASCADE`) → `strokes` (`ON DELETE CASCADE`).
- Índice único `(notebookId, pageIndex)` garante uma página por posição.
- Traços são gravados como `ByteArray`: `[int count][x, y, pressure] * count`
  em floats little-endian (`StrokeCodec`).
- `savePage` é atômico (`database.withTransaction`): atualiza a página, apaga e
  reinsere os traços e atualiza o `updatedAt` da nota. Se a nota foi excluída
  enquanto o editor estava em segundo plano, a gravação é ignorada.
- Escritas passam por um `Mutex` nos ViewModels, evitando que um autosave
  atrasado sobrescreva a página recém-carregada.
- A migração 1 → 2 preserva as notas já existentes (todas ficam na raiz).

## Compilação

Requisitos: JDK 17, Android SDK 34, Gradle 8.7+.

```bash
gradle assembleDebug
```

O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

O repositório não inclui Gradle Wrapper; o workflow
`.github/workflows/build-apk.yml` instala Gradle 8.7, compila e publica o APK
como artefato a cada push em `main`/`master`.

## Compatibilidade
- `minSdk 26`, `targetSdk 34`.
- Otimizado para S-Pen (Samsung) e telas grandes; funciona com toque simples
  apenas para navegação.
