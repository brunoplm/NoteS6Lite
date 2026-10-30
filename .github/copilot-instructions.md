# Protocolo de Mudanças e Backup para IAs / Agentes

Sempre que você (IA ou desenvolvedor) for realizar alterações neste projeto NoteS6Lite:

1. **BACKUP ANTES DE QUALQUER MUDANÇA**:
   - Crie uma branch de backup contendo a data e o contexto antes de tocar no código:
     `git branch backup/<data>-<motivo>`
   - Se a mudança for ampla, envie para o repositório remoto: `git push origin backup/<data>-<motivo>`.
   - Se ocorrer algum erro grave ou quebra de build inesperada, faça `git reset --hard` para o ponto de restauração do backup.

2. **VERSIONAMENTO**:
   - Todo ciclo de melhoria deve avançar o `versionCode` (+1) e o `versionName` no arquivo `app/build.gradle.kts`.

3. **RELEASE AUTOMÁTICA**:
   - Para publicar a versão pronta para o usuário no GitHub, crie uma tag `v<versionName>` (ex: `v1.3`) e envie com `git push origin v<versionName>`.
   - O GitHub Actions compilará e criará automaticamente a **Release com o APK anexado para download**.
