# EasyDrop

**Compartilhamento de arquivos por proximidade entre Androids.** Aproxime, confirme, pronto.

Sem conta, sem nuvem, sem servidor. A descoberta e a transferência acontecem inteiramente
entre os dois aparelhos.

> **Status:** início de desenvolvimento. A Fase 0 (validação de hardware e transporte) está
> concluída; o app de hoje é uma ferramenta de diagnóstico, não o produto final.

---

## O que é

O EasyDrop quer reproduzir o gesto do AirDrop e do NameDrop em Android: você aproxima dois
celulares, o outro aparece, você confirma e o arquivo vai. O diferencial não é a transferência
em si — é a **proximidade como interface**: nada de procurar dispositivo em lista, digitar IP
ou parear manualmente.

Tudo é local:

- descoberta por **Bluetooth LE** (e mDNS quando os dois já estão na mesma rede);
- transporte por **LAN comum** ou **hotspot local** criado na hora;
- conteúdo cifrado de ponta a ponta, com confirmação explícita de quem recebe.

## Status do projeto

| Fase | Escopo | Estado |
|---|---|---|
| 0 | Validação de hardware e transporte | **concluída** |
| 1 | MVP EasyDrop ↔ EasyDrop | próxima |
| 2 | Proximidade fina: NFC, RSSI calibrado, UWB | planejada |
| 3 | Interoperabilidade com Quick Share / Quick Drop | opcional |

O plano técnico completo, com decisões, riscos e medições, está em [`PLANO.md`](PLANO.md).

## Como funciona

1. **Descoberta.** O emissor anuncia um serviço BLE com UUID próprio do EasyDrop. O receptor
   escaneia, mede o RSSI e mostra o card quando o aparelho está perto o suficiente.
2. **Transporte.** Se os dois já estão na mesma rede Wi-Fi, usa TCP nela. Senão o emissor sobe
   um hotspot local e o receptor entra via `WifiNetworkSpecifier` — o Android mostra um único
   diálogo de confirmação, que se encaixa no fluxo de "Receber".
3. **Confirmação.** Handshake X25519, chave de sessão derivada por HKDF e um código de 6
   dígitos (SAS) que os dois lados comparam. Nada é transferido sem confirmação.
4. **Transferência.** Frames de 1 MiB, SHA-256 por arquivo, retomada por offset.

## O que já foi validado

Medições reais em 30/10/2026, com os aparelhos a ~20 cm:

| Caminho | Resultado |
|---|---|
| BLE (descoberta) | 13 anúncios em 10 s, RSSI −32 a −39 dBm, simétrico |
| Hotspot + `WifiNetworkSpecifier` | **10,6 MB/s** (~85 Mbps), 1 diálogo do sistema |
| LAN comum | 4,4 MB/s (limitado pelo AP de teste, não pelo protocolo) |

### Por que não Wi-Fi Aware

O plano inicial era usar Wi-Fi Aware (NAN) como transporte principal. O teste derrubou a
ideia: o **Motorola Edge 50 Ultra não expõe o serviço `wifi_aware`** — o HAL do Wi-Fi até
reporta suporte, mas a Motorola não registra o serviço no framework, então nenhum app
consegue usá-lo. Como o Edge é o aparelho de referência (é o que tem Quick Drop), NAN saiu do
caminho principal e virou bônus opcional.

## Dispositivos de teste

| Aparelho | Android | Observação |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | sem Wi-Fi Aware; tem UWB |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Aware funciona; root via KernelSU |

## Estrutura

```
app/        app Android (Kotlin + Jetpack Compose) — hoje, o diagnóstico da Fase 0
PLANO.md    plano técnico: protocolos, arquitetura, riscos, medições
```

## Compilar

Requisitos: **JDK 21** e **Android SDK 35** (platform + build-tools).

```bash
# aponte para o seu SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Ferramenta de diagnóstico

O app atual serve para medir o hardware. As ações podem ser disparadas por adb, sem tocar na
tela:

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
- [ ] Fase 2 — NFC tap-to-share, RSSI calibrado, UWB no Edge
- [ ] Fase 3 — interoperar com Quick Share (opcional)

## Licença

[GPL-3.0](LICENSE). Se você reusar ou modificar o EasyDrop, o projeto derivado também precisa
continuar aberto.
