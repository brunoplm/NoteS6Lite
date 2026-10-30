# Diretrizes Obrigatórias de Desenvolvimento (NoteS6Lite)

Este arquivo serve como instrução fundamental para humanos e quaisquer agentes/IAs que colaborem neste repositório.

---

## 1. Regra de Ouro: Backup Antes de Qualquer Mudança Significativa
Antes de implementar refatorações, novas funcionalidades ou correções estruturais:
1. **Branch de Backup Local e Remota**: Crie uma cópia exata do estado funcional atual:
   ```bash
   git branch backup/pre-<descricao>-$(date +%Y%m%d-%H%M)
   git push origin backup/pre-<descricao>-$(date +%Y%m%d-%H%M)
   ```
2. Caso algo dê errado ou o aplicativo fique instável, essa branch garante retorno imediato ao estado estável anterior sem risco de perda de código.

---

## 2. Versionamento Progressivo e Semântico
Toda nova funcionalidade ou entrega precisa ter seu versionamento atualizado em `app/build.gradle.kts`:
- Incrementar `versionCode` (ex: 3 -> 4).
- Atualizar `versionName` progressivamente (ex: "1.2" -> "1.3" ou "1.2.1" para correções pontuais).
- O nome de versão identifica visualmente cada avanço no aplicativo.

---

## 3. Criação Automática de Release no GitHub com APK
Sempre que uma versão estiver pronta para entrega ao usuário:
1. Faça o commit e o push para as branches de publicação (`main` e `master`).
2. Crie uma **Tag Git** com o formato `v<versionName>`:
   ```bash
   git tag -a v1.3 -m "Release v1.3: <resumo das novidades>"
   git push origin v1.3
   ```
3. O workflow do GitHub Actions (`.github/workflows/build-apk.yml`) está configurado para:
   - Compilar o APK (`app-debug.apk`).
   - Disponibilizar como Artifact.
   - **Publicar automaticamente uma Release no GitHub** com o instalador `.apk` anexado para download direto no tablet/celular.

---

## 4. Filosofia do Projeto
- **Zero Lag & Baixa Latência**: Desenho nativo usando `Canvas`, `getHistoricalX/Y`, e otimização para S-Pen (Samsung Galaxy Tab S6 Lite).
- **Sem Dependências Pesadas**: Manter a base pura (Kotlin + AndroidX + Room + Coroutines).
- **Rejeição de Palma**: Prioridade absoluta para `TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` sobre toques de dedos.
