# Como gerar o APK pelo GitHub (sem programar)

## O app está pronto para usar?

O código está escrito, mas o aplicativo só existe depois de **compilar**
(transformar o código em um arquivo `.apk`). Quem faz isso é o "robô" do
GitHub (GitHub Actions), e ele também serve de teste: se aparecer **bolinha
verde**, o APK foi gerado; se aparecer **X vermelho**, o código tem um erro
que precisa ser corrigido.

## Como funciona

1. Você envia os arquivos para o GitHub.
2. O GitHub liga sozinho o robô que compila.
3. Em 3 a 8 minutos ele gera o `app-debug.apk`.
4. Você baixa e instala no tablet.

---

## Jeito mais fácil de enviar a pasta do PC: GitHub Desktop

Se você está no PC com a pasta `D:\NotesS6Lite`:

1. Baixe e instale o **GitHub Desktop** (desktop.github.com) e entre na conta.
2. Menu **File → Add local repository…**
3. Escolha a pasta `D:\NotesS6Lite` e clique em **Add**.
4. Clique em **Publish repository** (botão em cima).
5. Nome: `NoteS6Lite`. Deixe **desmarcado** "Keep this code private"
   (público = compilações grátis e ilimitadas).
6. **Publish repository**.

Os arquivos sobem com o histórico de alterações e o robô começa sozinho.

---

## Passo 1 — Conta e repositório (só uma vez)

1. Acesse **github.com** e crie a conta (se ainda não tiver).
2. Clique no **+** no canto superior direito → **New repository**.
3. Nome: `NoteS6Lite`.
4. Marque **Public** (recomendado).
5. **Não** marque "Add a README".
6. Clique em **Create repository**.

## Passo 2 — Enviar os arquivos (só uma vez)

1. Abra a pasta `D:\NotesS6Lite` no computador.
2. Selecione tudo com **Ctrl+A**.
3. No GitHub, na página do repositório, clique em **Add file → Upload files**.
4. Arraste os arquivos para a página e espere a lista aparecer.
5. Clique no botão verde **Commit changes**.

> A pasta `.github` (que começa com ponto) é a que contém o robô. Se ela não
> aparecer no repositório depois do envio, crie o arquivo à mão:
>
> 1. **Add file → Create new file**
> 2. No nome digite exatamente: `.github/workflows/build-apk.yml`
> 3. Cole o conteúdo que está no fim deste guia.
> 4. **Commit changes**.

## Passo 3 — Ver o robô trabalhando

1. No repositório, clique na aba **Actions**.
2. Aparece "Compilar APK NoteS6Lite" com uma bolinha amarela (compilando).
3. Espere de 3 a 8 minutos.
4. **Bolinha verde** = deu certo. **X vermelho** = deu erro.

## Passo 4 — Baixar o APK

1. Clique na compilação que ficou verde.
2. Role até o fim da página, seção **Artifacts**.
3. Clique em **NoteS6Lite-APK** → baixa um arquivo `.zip`.
4. Dentro do zip está o `app-debug.apk`.
   - No PC: descompacte e copie o `.apk` para o tablet (cabo USB ou Drive).
   - No tablet: baixe, descompacte com o app "Arquivos" e toque no `.apk`.

## Passo 5 — Instalar no tablet

1. Ao tocar no APK, o Android avisa que é de "fonte desconhecida".
2. Permita para o app de arquivos/navegador usado.
3. **Instalar** → **Abrir**.

---

## Se aparecer X vermelho (erro)

1. Clique na compilação vermelha.
2. Clique no job **build**.
3. Abra a etapa **4. Compilar o Aplicativo (.APK)**.
4. Copie as linhas que começam com `e:`, `error:` ou `FAILURE`.
5. Me mande esse texto (ou um print). Eu corrijo e você faz um novo commit.

---

## Depois de instalado: o que testar

- Tela inicial: criar pasta, entrar nela, criar subpasta (vários níveis).
- Migalhas de pão: tocar em "Início" e nos níveis do meio.
- Criar nota dentro de uma pasta, escrever, voltar e ver se ela aparece lá.
- Segurar um item: renomear, mover para outra pasta e excluir.
- Escrever com a S-Pen (a linha deve acompanhar sem atraso).
- Apoiar a mão na tela enquanto escreve (não pode sair risco).
- Mudar a pressão da caneta.
- Trocar Template e Folha.
- Sair do app e voltar (as anotações têm que continuar).
- Importar um PDF e escrever por cima.
- Salvar PDF.

## Como atualizar depois (do celular, fora de casa)

1. Peça a alteração ao Gemini.
2. Ele te dá o arquivo novo; no GitHub, abra o arquivo (ícone de lápis),
   apague tudo, cole o novo e clique em **Commit changes**.
3. O robô compila sozinho de novo.
4. Baixe o APK novo em **Actions → Artifacts**.

---

## Conteúdo do arquivo do robô

```yaml
name: Compilar APK NoteS6Lite

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - name: 1. Baixar codigo do repositorio
        uses: actions/checkout@v4

      - name: 2. Configurar Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: 3. Configurar Gradle 8.7 na Nuvem
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'

      - name: 4. Compilar o Aplicativo (.APK)
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: 5. Disponibilizar APK para Download
        uses: actions/upload-artifact@v4
        with:
          name: NoteS6Lite-APK
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
```
