# EasyDrop

**Compartilhamento de arquivos por proximidade entre Androids.** Aproxime, confirme, pronto.

Sem conta, sem nuvem, sem servidor. A descoberta e a transferência acontecem inteiramente
entre os dois aparelhos.

> **Status:** início de desenvolvimento. A Fase 0 (validação de hardware e transporte) está
> concluída e o laboratório de protocolos já validou os quatro caminhos nos dois aparelhos. O
> app de produto ainda não existe: o que está no repositório é diagnóstico + laboratório.

---

## O que é

O EasyDrop quer reproduzir o gesto do AirDrop e do NameDrop em Android: você aproxima dois
celulares, o outro aparece, você confirma e o arquivo vai. O diferencial não é a transferência
em si — é a **proximidade como interface**: nada de procurar dispositivo em lista, digitar IP
ou parear manualmente.

Tudo é local:

- gatilho por **toque NFC** (com BLE como alternativa);
- transporte por **Wi-Fi Direct** (receptor cria o grupo em 5 GHz), com hotspot local ou LAN
  comum como reserva;
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

1. **Descoberta.** Quem recebe anuncia um serviço BLE com UUID próprio do EasyDrop. Quem envia
   escaneia, mede o RSSI e mostra o card quando o aparelho está perto o suficiente. No modo
   toque, quem recebe emula um cartão NFC e as credenciais saem no encostar.
2. **Transporte.** **Quem recebe** cria o grupo Wi-Fi Direct em 5 GHz e publica nome, SSID,
   senha e frequência pelo toque; quem envia entra direto com SSID/senha, sem descoberta, WPS ou
   diálogo de confirmação. Se o P2P não estiver disponível, quem recebe sobe um hotspot local e
   quem envia entra via `WifiNetworkSpecifier` — o Android mostra um único diálogo, que se
   encaixa no fluxo de "Receber". Se os dois já estiverem na mesma rede Wi-Fi, usa TCP nela.
3. **Confirmação.** Handshake X25519, chave de sessão derivada por HKDF e um código de 6
   dígitos (SAS) que os dois lados comparam. Nada é transferido sem confirmação.
4. **Transferência.** Frames de 1 MiB, SHA-256 por arquivo, retomada por offset.

## O que já foi validado

Medições reais em 01/11/2026, com os aparelhos a ~20 cm:

| Caminho | Resultado |
|---|---|
| BLE (descoberta) | 13 anúncios em 10 s, RSSI −32 a −39 dBm, simétrico |
| NFC (toque → credenciais) | **0,02 s** |
| Wi-Fi Direct (toque → grupo) | **0,86 s** de mediana, sem diálogo, **35–48 MB/s** |
| Hotspot + `WifiNetworkSpecifier` | **10–12 MB/s**, 1 diálogo do sistema |
| LAN comum | 1,6–5,7 MB/s (limitado pelo AP de teste, não pelo protocolo) |

O laboratório também tem testes unitários de regressão para o transporte TCP (transferência
completa, cancelamento no meio, timeout de ACK e timeout de inatividade), para a configuração
do grupo P2P e para a seleção de modo/papel e permissões.

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
app/            diagnóstico da Fase 0 (capacidades de rádio, hotspot, BLE, throughput)
lab/            laboratório de protocolos: testa um caminho de conexão por vez
PLANO.md        plano técnico: protocolos, arquitetura, riscos, medições
MELHORIAS.md    análise, correções propostas e experimentos, com o resultado de cada um
```

### Laboratório (`lab/`)

Tela inteira colorida pelo estado — cinza parado, azul procurando, âmbar peer encontrado,
laranja conectando, **verde conectado**, verde escuro transferindo, vermelho erro. Escolha o
modo e o papel, toque em Iniciar, e cada teste transfere 16 MB medindo tempo de setup e MB/s.

```bash
PKG=dev.leandro.easydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode BLE --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode BLE --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s EasyDropLab:I
```

Modos: `LAN` (mDNS + TCP), `BLE` (BLE + GATT + hotspot), `NFC` (toque + hotspot) e `P2P`
(toque + Wi-Fi Direct). No toque, quem **recebe** cria o grupo/hotspot e quem **envia** entra
e empurra os dados. No Wi-Fi Direct o receptor publica nome, SSID, senha e frequência; o
emissor entra direto com SSID/senha, sem descoberta, WPS ou diálogo de confirmação.

Os quatro modos usam o mesmo `TcpLink`: ACK final de 10 s, timeout de inatividade de 30 s,
`Parar` que fecha o socket ativo e a mesma medição de taxa. O `setupMs` é só o tempo até a
conexão, separado do total.

```bash
./gradlew :lab:testDebugUnitTest
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
