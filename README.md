# EasyDrop

Compartilhamento de arquivos por proximidade entre dispositivos Android. A transferência
ocorre inteiramente entre os dois aparelhos: sem conta, sem nuvem, sem servidor.

> **Status:** Fase 0 concluída. O repositório contém a ferramenta de diagnóstico (`:app`) e o
> laboratório de protocolos (`:lab`). O app de produto (Fase 1) ainda não foi implementado.

---

## Arquitetura

1. **Descoberta.** NFC (HCE + reader mode) é o gatilho principal: o receptor publica nome,
   SSID, senha e frequência do grupo. BLE é a alternativa para dispositivos sem NFC.
2. **Transporte.** O receptor hospeda a conexão; o emissor envia.
   - Wi-Fi Direct: grupo em 5 GHz (5745 MHz, não-DFS), com fallback automático de canal.
   - LocalOnlyHotspot + `WifiNetworkSpecifier`: reserva para dispositivos sem P2P.
   - LAN comum: TCP direto quando os dois já estão na mesma rede.
3. **Segurança.** Noise NK (X25519 + ChaCha20-Poly1305 + SHA-256) sobre TCP. A chave
   pública efêmera do receptor vai no payload NFC e autentica o toque; o receptor aceita
   sem diálogo. O laboratório ainda transfere texto claro; o `:protocol` já tem o handshake
   e o canal cifrado.
4. **Transferência.** Framing por comprimento, SHA-256 por arquivo e retomada por offset
   (Fase 1). O laboratório usa um `TcpLink` compartilhado, com ACK final, timeouts e
   cancelamento.

## Transportes

| Transporte | Uso | Estado |
|---|---|---|
| NFC (HCE + reader mode) | gatilho e troca de credenciais | validado |
| BLE | descoberta alternativa | validado |
| Wi-Fi Direct | dados | validado |
| LocalOnlyHotspot | dados (reserva) | validado |
| LAN (TCP) | dados | validado |
| Wi-Fi Aware (NAN) | descoberta e dados | **não validado**: requer hardware que exponha o serviço `wifi_aware` |
| UWB | ranging de proximidade | **não validado**: requer hardware compatível |

Wi-Fi Aware e UWB permanecem no roadmap como opcionais; a validação depende de hardware
disponível.

## Validação

Medições de 01/11/2026, transferência de 16 MB, aparelhos a ~20 cm:

| Caminho | Setup | Throughput |
|---|---|---|
| BLE (descoberta) | 0,1–0,6 s | — |
| NFC (toque → credenciais) | 0,02 s | — |
| Wi-Fi Direct (toque → grupo) | 0,86 s (mediana) | 35–48 MB/s |
| LocalOnlyHotspot + `WifiNetworkSpecifier` | 7–10 s (diálogo do sistema) | 10–12 MB/s |
| LAN comum | 0,3–3,6 s | 1,6–5,7 MB/s |

Os valores são específicos do par de aparelhos de teste e do ambiente de rede; não
representam todos os Androids.

### Dispositivos de teste

| Aparelho | Android | Observação |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | Wi-Fi Direct; sem serviço `wifi_aware`; UWB presente |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Direct; Wi-Fi Aware disponível |

## Estrutura

```
app/            ferramenta de diagnóstico (capacidades de rádio, hotspot, BLE, throughput)
lab/            laboratório de protocolos: testa um caminho de conexão por vez
protocol/       protocolo v1 em JVM pura: payload NFC, Noise NK, frames e canal seguro
docs/           PROTOCOL.md, ARCHITECTURE.md, SECURITY.md e NAMING.md
PLANO.md        plano técnico: protocolos, arquitetura, riscos, medições
MELHORIAS.md    análise, correções e experimentos, com o resultado de cada um
```

## Laboratório (`lab/`)

Tela colorida por estado: cinza parado, azul procurando, âmbar peer encontrado, laranja
conectando, verde conectado, verde escuro transferindo, vermelho erro. Cada execução transfere
16 MB e reporta setup e MB/s.

```bash
PKG=dev.leandro.easydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s EasyDropLab:I
```

Modos: `LAN` (mDNS + TCP), `BLE` (BLE + GATT + hotspot), `NFC` (toque + hotspot) e `P2P`
(toque + Wi-Fi Direct). No toque, o receptor cria o grupo/hotspot e o emissor entra e envia os
dados. No Wi-Fi Direct o receptor publica nome, SSID, senha e frequência; o emissor entra
direto com SSID/senha, sem descoberta, WPS ou diálogo de confirmação.

Os quatro modos usam o mesmo `TcpLink`: ACK final de 10 s, timeout de inatividade de 30 s,
`Parar` que fecha o socket ativo e a mesma medição de taxa. `setupMs` cobre apenas a conexão.

Testes:

```bash
./gradlew :protocol:test :lab:testDebugUnitTest
```

## Compilar

Requisitos: JDK 21 e Android SDK 35 (platform + build-tools).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :lab:assembleDebug :app:assembleDebug
adb install -r lab/build/outputs/apk/debug/lab-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Ferramenta de diagnóstico

Ações disparáveis por adb:

```bash
PKG=dev.leandro.easydrop.diag

# capacidades do aparelho (Wi-Fi Aware, Direct, NFC, BLE, UWB)
adb shell am start -n $PKG/.MainActivity

# BLE: um lado anuncia, o outro escaneia
adb shell am start -n $PKG/.MainActivity --es action bleadv
adb shell am start -n $PKG/.MainActivity --es action blescan

# hotspot local: sobe e mostra SSID/senha no log
adb shell am start -n $PKG/.MainActivity --es action hotspot

# entra num hotspot (mostra o diálogo do sistema)
adb shell am start -n $PKG/.MainActivity --es action join --es ssid "..." --es pass "..."

# transferência: um lado serve, o outro envia 32 MB
adb shell am start -n $PKG/.MainActivity --es action serve
adb shell am start -n $PKG/.MainActivity --es action send:192.168.0.10

# acompanhar
adb logcat -s EasyDropDiag:I
```

## Roadmap

- [x] Fase 0 — validar hardware, escolher transporte, medir throughput
- [ ] Fase 1 — MVP: descoberta, handshake, transferência com hash, UI
- [ ] Fase 2 — NFC tap-to-share, RSSI calibrado, UWB
- [ ] Fase 3 — interoperabilidade com Quick Share (opcional)

## Licença

[GPL-3.0](LICENSE). Projetos derivados devem permanecer abertos.
