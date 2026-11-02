# Arquitetura

> Estado atual: laboratório (`:lab`) e diagnóstico (`:app`).
> Alvo da Fase 1: `:protocol` (JVM puro) + `:core` (Android) + `:app` (produto).

## 1. Módulos

| Módulo | Tipo | Responsabilidade |
|---|---|---|
| `:protocol` | JVM puro | Payload de descoberta, handshake Noise, framing, mensagens, máquina de estados de transferência |
| `:core` | Android library | Transporte (P2P, hotspot, LAN), descoberta (NFC, BLE, mDNS), orquestração, MediaStore, foreground service |
| `:app` | Android app | UI Compose, share target, notificações, ajustes |
| `:lab` | Android app | Experimentos de protocolo, benchmarks, testes de rádio |
| `:diag` | Android app | Diagnóstico de capacidades e exportação de relatório |

O `:app` atual é o diagnóstico. A migração renomeia esse módulo para `:diag` e cria um
`:app` novo; o `:lab` continua existindo como ferramenta de desenvolvimento.

## 2. Dependências

```
:protocol  (sem Android)
    ↑
:core  (Android)
    ↑
:app  (Android)

:lab  → :protocol   (opcional, para reutilizar codec e Noise)
:diag → :protocol   (opcional, para relatório de protocolo)
```

Regras:

- `:protocol` não importa `android.*` nem `androidx.*`.
- `:core` não conhece UI.
- `:app` não conhece sockets, P2P nem MediaStore diretamente; fala com `:core`.
- `:lab` e `:diag` não são dependências de `:core` nem de `:app`.

## 3. Responsabilidades por camada

### `:protocol`

- `DiscoveryPayload` — encode/decode do payload NFC.
- `NoiseHandshake` / `NoiseTransport` — NK e cifra.
- `FrameCodec` — encode/decode das mensagens.
- `SecureChannel` — framing + cifra sobre `InputStream`/`OutputStream`.
- `TransferState` — estados e transições da transferência.
- Testes 100% JVM, sem Robolectric.

### `:core`

- `TcpLink` — transporte TCP com ACK, idle e cancelamento (extraído do `:lab`).
- `P2pTransport`, `HotspotTransport`, `LanTransport` — implementações de `Transport`.
- `NfcDiscovery`, `BleDiscovery`, `MdnsDiscovery` — implementações de `Discovery`.
- `TransferService` — foreground service `connectedDevice`.
- `FileSink` — MediaStore, sanitização, colisão, espaço livre.
- `Orchestrator` — escolhe o transporte a partir da descoberta e conduz a sessão.

### `:app`

- Share target (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
- Tela de aproximação, progresso, resultado.
- Notificação com cancelar.
- Strings, tema, acessibilidade.

## 4. Fluxo de uma transferência

```
[emissor] share sheet
    → :app recebe URI
    → :core abre ContentResolver
    → :core descobre o receptor (NFC)
    → :core conecta no grupo P2P
    → :protocol faz Noise NK
    → :protocol envia Offer
    → :protocol envia Data*
    → :protocol envia End(sha256)
    → :core grava no MediaStore e confere o hash
    → :protocol recebe Ack
    → :app mostra resultado
```

No receptor:

```
:core arma a janela (grupo + chaves + NFC + servidor)
    → :protocol aceita o handshake
    → :protocol lê Offer
    → :core valida espaço e nome
    → :protocol envia Accept
    → :core grava Data* em arquivo temporário/MediaStore
    → :protocol recebe End e confere hash
    → :protocol envia Ack
    → :core finaliza o MediaStore
```

## 5. O que fica no laboratório

- BLE com credenciais no GATT (não vai ao produto enquanto não houver desenho seguro).
- Benchmarks de throughput.
- Testes de canal, frequência e coexistência.
- Wi-Fi Aware e UWB.

## 6. Migração

1. Criar `:protocol` com codec, Noise e testes (Fase A).
2. Criar `:core` e mover `TcpLink` para lá, mantendo o `:lab` funcionando (Fase C).
3. Criar `:app` de produto e renomear o diagnóstico para `:diag` (Fase E).
4. Ligar `:lab` ao `:protocol` para validar o mesmo codec usado no produto.

Cada passo é um commit atômico com a suíte verde.
