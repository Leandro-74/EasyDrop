# Avisos de terceiros

## Runtime

### noise-java

- Coordenada: `org.signal.forks:noise-java:0.1.1`
- Origem: <https://github.com/signalapp/noise-java> (fork de `rweather/noise-java`)
- Licença: MIT
- Uso: handshake Noise NK e transporte ChaCha20-Poly1305 em `:protocol`.
- Compatibilidade: MIT é compatível com GPL-3.0.

### Kotlin standard library

- Coordenada: `org.jetbrains.kotlin:kotlin-stdlib` (transitiva)
- Licença: Apache 2.0
- Uso: runtime Kotlin.

## Testes

### JUnit 4

- Coordenada: `junit:junit:4.13.2`
- Licença: Eclipse Public License 1.0
- Uso: testes unitários de `:protocol` e `:lab`.

### Robolectric

- Coordenada: `org.robolectric:robolectric:4.14.1`
- Licença: MIT
- Uso: testes unitários de `:lab` que dependem de APIs Android.

## Pendências

- Auditar as dependências de `:app` (Compose, AndroidX, Activity, Lifecycle).
- Revisar a licença de cada biblioteca antes do primeiro release.
- Gerar o arquivo completo a partir do relatório do Gradle.
