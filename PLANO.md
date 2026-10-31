# EasyDrop — Plano Técnico

> Documento de planejamento. Nada foi implementado ainda. Nenhum repositório foi criado.

---

## 1. Resumo executivo

O EasyDrop é um app de compartilhamento de arquivos por proximidade: você aproxima dois
celulares, confirma, e o arquivo vai. O objetivo estético é o NameDrop/AirDrop; o objetivo
funcional mínimo é **EasyDrop ↔ EasyDrop** funcionando bem nos dois aparelhos de teste.

A pergunta central — "dá para falar com o Quick Drop da Motorola?" — tem resposta **sim, mas
por um caminho específico**: o Quick Drop é uma camada de marca da Motorola sobre o **Google
Quick Share**, e o protocolo de rede do Quick Share é implementável de forma independente
(já existem implementações open source que conversam com Android). O que **não** é viável é o
caminho do AirDrop/iPhone via `mosey`, que exige stack de kernel e rádio específicos.

Recomendação em uma linha: **Fase 1 com protocolo próprio sobre BLE (descoberta) + Wi-Fi
Direct/LocalOnlyHotspot (dados); interoperabilidade com Quick Share só depois de validar em
hardware; Apple fica fora do escopo por decisão de produto.**

> **Correção depois da Fase 0:** o Edge 50 Ultra não expõe Wi-Fi Aware. O HAL do Wi-Fi suporta
> NAN, mas a Motorola não registra o serviço `wifi_aware` no framework. Como o Edge é o
> aparelho de referência, NAN deixa de ser o transporte principal. Ver seção 3.3 e os
> resultados da Fase 0.

Decisões já fechadas:

- **Escopo:** Android apenas. Sem iOS, sem macOS, sem AirDrop.
- **Fase 1:** só EasyDrop ↔ EasyDrop.
- **Licença:** GPL-3.0.

---

## 2. O que é o Quick Drop, em camadas

| Camada | O que é | Relevância para nós |
|---|---|---|
| App `com.motorola.touchshare` | Stub de ~101 KB. Só integra a UI da Motorola ao share sheet. | Baixa. Decompilar isso não revela protocolo nenhum. |
| Google Quick Share (`com.google.android.gms` + Quick Share Extension) | O protocolo real: descoberta, pareamento, criptografia, transferência. | **Alta.** É aqui que a interoperabilidade acontece. |
| Quick Share "mosey" (interop AirDrop) | Stack proprietário em Rust, `mosey_server`, interface `wonder`, `wonder.ko`. | Nula. Não é acessível por API pública. |
| Firmware / driver Wi-Fi do fabricante | `wondertap` em chips Broadcom suportados. | Nula. Não temos acesso. |

Conclusão prática: **interoperar com Quick Drop = interoperar com Quick Share.** O "Drop" da
Motorola não acrescenta protocolo; acrescenta toque NFC e compartilhamento em grupo.

---

## 3. Panorama dos protocolos

### 3.1 Google Quick Share (o alvo realista)

- Descoberta por **mDNS** (`_FC9F5ED42C8A._tcp`) em LAN, e por **BLE** (service UUID `fe2c`)
  quando não há LAN comum.
- Handshake **UKEY2**, frames **protobuf** (`OfflineFrames`), cifra **AES-CBC + HMAC-SHA256**,
  PIN de 4 dígitos para confirmação.
- Transporte TCP com framing por comprimento.
- Documentação de engenharia reversa pública: `NearDrop/PROTOCOL.md`.
- Implementações existentes: **NearDrop** (macOS, Unlicense), **Packet** (Linux),
  **rquickshare** (Rust, GPL-3.0), **Bada** (Android/Kotlin, sem GMS).

Isso é viável. É o que NearDrop e Packet já fazem.

### 3.2 AirDrop / AWDL / mosey (o alvo inviável)

- AirDrop roda sobre **AWDL**, uma camada de rádio da Apple que não existe em Android.
- O Google implementou interop via **mosey**, que exige:
  - `/vendor/bin/mosey_server` + `mosey.rc` + política SELinux;
  - um phy virtual `wonder` e o módulo `wonder.ko`;
  - driver Wi-Fi com suporte `wondertap` (Broadcom em Pixel 9/10 e alguns Samsung/BBK).
- O Edge 50 Ultra (Snapdragon 8s Gen 3) e o S21 (SD888/Exynos 2100) **não** estão na lista de
  chips com `wondertap`. Sem isso, o `wonder` é um phy virtual sem RF real.
- Ou seja: **não dá para fazer AirDrop em Android stock por API pública.** Nem com root no S21,
  porque o problema é driver/rádio, não permissão.

### 3.3 Wi-Fi Aware (NAN) — funciona no S21, desligado no Edge

- API pública desde Android 8 (`FEATURE_WIFI_AWARE`), com `publish`/`subscribe` e data path
  IPv6 link-local. Ranging (802.11mc/az) desde o Android 13.
- **S21:** funciona. `isAvailable = true`, attach/publish/subscribe sem erro.
- **Edge 50 Ultra:** não funciona. `hasSystemFeature` = false, `getSystemService` devolve
  `null` e o binder `wifi_aware` não aparece no `service list` (só `wifi`, `wifip2p`,
  `wifirtt`, `wifiscanner`). O HAL até reporta `WIFI_FEATURE_AWARE` no `dumpsys wifi`, mas a
  Motorola não registra o serviço — o rádio suporta, o framework não expõe.
- **Consequência:** NAN não pode ser o transporte principal, porque o aparelho de referência
  (o que tem Quick Drop) não o oferece. Só voltaria com ROM modificada/root, o que não é
  produto.

### 3.4 Alternativas de transporte

| Transporte | Vantagem | Limite |
|---|---|---|
| Wi-Fi Direct | Throughput alto, presente nos dois aparelhos | UX de pareamento chata; conflita com Wi-Fi normal |
| LocalOnlyHotspot | Simples, funciona em quase tudo | Sem internet; derruba o Wi-Fi do emissor em muitos aparelhos |
| BLE | Descoberta barata e onipresente | Lento para dados; advertising em background é restrito |
| Wi-Fi Aware | Descoberta + dados + ranging, sem infraestrutura | **Indisponível no Edge 50 Ultra**; fica como bônus opcional |
| NFC (HCE + reader mode) | "Encostou, conectou" — o gesto que o usuário quer | Só bootstrap; exige tela ligada; não é P2P (Beam morreu) |
| UWB | Ranging de centímetros | **S21 não tem**; só serve como upgrade no Edge |

### 3.5 Nearby Connections (API do Google Play Services)

Existe uma API pronta (`com.google.android.gms.nearby.connection`) que já faz descoberta,
seleção de transporte (BLE, Wi-Fi Direct, Wi-Fi LAN, Wi-Fi Aware) e criptografia.

- **A favor:** protótipo em dias, transporte resolvido, robusto.
- **Contra:** não expõe RSSI/distância (não dá o gesto de proximidade), amarra o produto ao
  GMS, pouca margem para otimizar throughput e retomada, e o formato de payload é engessado.

Uso recomendado: **spike de benchmark**, não fundação do produto.

---

## 4. Opções de arquitetura

| # | Opção | Esforço | Risco | Entrega |
|---|---|---|---|---|
| A | Protocolo próprio, EasyDrop ↔ EasyDrop | Médio | Baixo | Diferencial completo, controle total |
| B | A + interop Quick Share/Quick Drop | Alto | Médio | Fala com qualquer Android sem o app |
| C | Interop AirDrop/iPhone | — | — | **Fora de escopo** (decisão de produto + bloqueio de hardware) |
| D | Só Nearby Connections | Baixo | Baixo | Funciona, mas genérico e dependente de GMS |

**Recomendação: A agora, B depois.** C está descartado por dois motivos independentes: o
usuário não quer compatibilidade com Apple, e tecnicamente ela exigiria rádio que esses
aparelhos não têm.

---

## 5. Arquitetura proposta

### 5.1 Princípios

1. **Descoberta barata, conexão rápida, transferência rápida.** São três problemas diferentes.
2. **Nenhum caminho único.** BLE descobre; se já houver LAN comum, usa TCP nela; senão sobe
   hotspot; se o hotspot falhar, tenta Wi-Fi Direct; se tudo falhar, QR code.
3. **Sem servidor, sem conta, sem nuvem.** Tudo local.
4. **Segurança por padrão.** Nada é transferido sem confirmação explícita do receptor.

### 5.2 Módulos

```
app/                     Compose, share sheet, tela de recebimento, progresso
core/discovery/          BLE advertise+scan, mDNS, NFC (HCE + reader mode), QR
core/transport/          LAN comum (TCP), LocalOnlyHotspot, Wi-Fi Direct, Aware opcional
core/crypto/             X25519 efêmero, HKDF-SHA256, ChaCha20-Poly1305, SAS de 6 dígitos
core/transfer/           framing, chunking 1 MiB, SHA-256 por arquivo, retomada, backpressure
core/proximity/          RSSI BLE (+ UWB quando houver) → "quão perto" → gatilho de UI
```

### 5.3 Fluxo de uma transferência

1. **Emissor** anuncia por BLE (e por mDNS, se já estiver numa LAN) com um identificador
   efêmero e o hash da própria chave pública.
2. **Receptor** escuta; ao detectar proximidade suficiente, mostra o card do emissor.
3. **Handshake:** X25519 → HKDF → chave de sessão. Ambos derivam um **SAS de 6 dígitos**.
4. **Confirmação:** o receptor confirma na tela; o SAS é conferido (ou o vínculo é feito por
   toque NFC, que dispensa digitar).
5. **Transporte:** se os dois já estão na mesma LAN, abre TCP nela. Senão o emissor sobe um
   LocalOnlyHotspot e o receptor entra via `WifiNetworkSpecifier`. Wi-Fi Direct é o plano B.
6. **Transferência:** frames de 1 MiB, SHA-256 por arquivo, ACK por janela, retomada por offset.
7. **Fim:** grava em `Downloads/EasyDrop/` via MediaStore, notifica, apaga buffers.

### 5.4 Segurança

- Sempre E2E. Nunca texto claro no fio, nem em LAN confiável.
- Confirmação **obrigatória** do receptor. Nada de recebimento silencioso.
- Chaves efêmeras por sessão; nada persistido além de um eventual "dispositivos confiáveis".
- SAS de 6 dígitos comparado visualmente, ou NFC como prova de presença física (mais forte
  que qualquer PIN, porque exige estar a centímetros).

### 5.5 UX de proximidade

O gesto "aproximar" tem três níveis de qualidade:

1. **NFC tap** — encostar os aparelhos. Melhor UX, mais restrito (tela ligada, posição do
   sensor varia muito entre modelos).
2. **UWB** — distância de centímetros. Só o Edge 50 Ultra tem; o S21 não, então é upgrade,
   não base.
3. **RSSI BLE** — grosseiro, mas funciona em tudo. Bom o suficiente para "está perto" (≈1 m),
   ruim para "está a 30 cm".

Ranging por Wi-Fi Aware sairia de graça no S21, mas o Edge não expõe o serviço — então não
conta para o produto.

Plano: RSSI como base universal, UWB como upgrade quando os dois aparelhos tiverem, NFC como
atalho premium.

### 5.6 Stack

- Kotlin, Jetpack Compose, Coroutines/Flow.
- `minSdk 29` (Android 10) para alcance; recursos de proximidade fina degradam graciosamente.
- Foreground service com tipo `connectedDevice` + `dataSync` para descoberta em background
  (obrigatório no Android 14+).
- Sem Hilt no começo — módulos com injeção manual bastam.
- **Licença GPL-3.0** — o que libera reusar código GPL (Bada, rquickshare, OpenDrop) na Fase 3,
  desde que o projeto inteiro continue aberto e os avisos de copyright sejam mantidos.

---

## 6. Roadmap

### Fase 0 — Validação de hardware (1–2 dias)

App de diagnóstico, sem UI bonita, que reporta e testa:

- capacidades por aparelho (`WIFI_AWARE`, `WIFI_DIRECT`, `NFC`, `BLE`, `UWB`);
- Aware: attach/publish/subscribe e data path com throughput;
- LocalOnlyHotspot: sobe? qual SSID? o outro entra?
- BLE: advertise e scan funcionam juntos?
- Wi-Fi Direct: descoberta e formação de grupo;
- coexistência com Wi-Fi conectado a um AP.

**Critério de saída:** sabemos exatamente qual transporte existe em cada aparelho.
Sem isso, o resto é chute.

**Resultados — S21 (SM-G991B, Exynos 2100, Android 15 / SDK 35):**

| Item | Resultado |
|---|---|
| Wi-Fi Aware | disponível (`isAvailable = true`) |
| attach / publish / subscribe | funcionam, sem erro |
| Wi-Fi Direct | disponível |
| Wi-Fi RTT (802.11mc) | disponível |
| NFC + HCE | hardware presente, HCE suportado |
| BLE | presente |
| UWB | ausente |
| Root | KernelSU (`su -c id` → uid=0) |

**Resultados — Edge 50 Ultra (motorola edge 50 ultra, Android 17 / SDK 37):**

| Item | Resultado |
|---|---|
| Wi-Fi Aware | **ausente no framework** (`feature=false`, serviço `wifi_aware` não registrado) |
| Wi-Fi Direct | disponível |
| Wi-Fi RTT (802.11mc) | disponível |
| NFC + HCE | hardware presente, HCE suportado |
| BLE | presente, ligado |
| UWB | **presente** |
| Root | não |

**Decisão da Fase 0:** Wi-Fi Aware sai do caminho principal — o aparelho de referência não o
oferece. O transporte passa a ser **BLE (descoberta) + LAN comum/LocalOnlyHotspot (dados)**,
com Wi-Fi Direct como plano B.

**Transporte medido (30/10/2026, aparelhos a ~20 cm):**

| Caminho | Como | Resultado | Observação |
|---|---|---|---|
| BLE (descoberta) | Edge anuncia, S21 escaneia | 13 anúncios em 10 s, RSSI −34 a −38 dBm | simétrico; service data chega intacto |
| Hotspot + WNS | Edge hospeda, S21 entra | 9,1–10,6 MB/s (~85 Mbps) | 1 diálogo do sistema no lado que entra |
| LAN comum | os dois no Wi-Fi de casa | 4,0–4,4 MB/s | limitado pelo AP (−66 dBm, Rx 6 Mbps), não pelo protocolo |

Conclusões:

- **O caminho mínimo fecha:** BLE descobre, hotspot conecta, TCP transfere.
- O diálogo do `WifiNetworkSpecifier` ("Conectar ao dispositivo") é aceitável e pode ser
  encaixado no fluxo de confirmação do receptor.
- Wi-Fi Direct deixa de ser necessidade e vira otimização de throughput.
- O hotspot do Edge convive com o Wi-Fi normal (`wlan1` continua conectado enquanto o
  `wlan2` serve o AP) — o risco de derrubar a internet não se confirmou nesse aparelho.

### Fase 1 — MVP EasyDrop ↔ EasyDrop (3–4 semanas)

- Descoberta BLE + conexão por LAN comum ou LocalOnlyHotspot; fallback Wi-Fi Direct.
- Handshake, SAS, confirmação, transferência com hash e retomada.
- UI: share sheet, card de recebimento, progresso, histórico.
- 1 arquivo → N arquivos → pasta → arquivo grande (>2 GB).

### Fase 2 — Proximidade e atalhos (2 semanas)

- RSSI BLE calibrado para o gesto de aproximar; UWB como upgrade no Edge.
- NFC tap-to-share (HCE `D2760101` + reader mode) como bootstrap.
- QR code como fallback universal.
- "Dispositivos confiáveis" para pular confirmação.

### Fase 3 — Interop Quick Share / Quick Drop (opcional, 4–6 semanas)

Só depois de a Fase 1 estar sólida.

- Validar em black-box: Bada/NearDrop/Packet conseguem enviar para o Edge 50 Ultra?
- Se sim: portar/reusar a camada de protocolo (NearDrop é Unlicense — a mais amigável).
- Implementar UKEY2 + protobuf + mDNS/BLE do Quick Share no nosso stack.
- **Não** redistribuir nada da Motorola ou do Google. Código próprio ou OSS compatível.

### Fora de escopo — Apple

Sem iOS, sem macOS, sem AirDrop. Não é só preferência de produto: exigiria app próprio no iOS
e validação em hardware que não está disponível. Se um dia mudar, o caminho seria Wi-Fi Aware
no iOS 26+, nunca AWDL.

---

## 7. Riscos

| Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|
| Wi-Fi Aware indisponível no Edge 50 Ultra | **confirmado** | Alto | Transporte base virou BLE + hotspot/Direct |
| LocalOnlyHotspot derruba o Wi-Fi do emissor | Alta | Médio | Preferir LAN comum quando existir; avisar na UI |
| Throughput do hotspot abaixo do esperado | Média | Médio | Medir na Fase 0; Wi-Fi Direct como plano B |
| BLE advertising restrito em background | Alta | Médio | Foreground service + notificação persistente |
| Coexistência hotspot × Wi-Fi STA × P2P | Média | Médio | Testar na Fase 0; escolher canal livre |
| Fragmentação de fabricante (Motorola/Samsung matam serviços) | Alta | Médio | Testar com battery saver, Doze, restrições de OEM |
| Quick Share mudar o protocolo | Média | Baixo (Fase 3) | Isolar a camada de interop |
| Risco legal (decompilar/redistribuir binários) | Baixa se disciplinado | Alto | Só código próprio ou OSS com licença compatível |

---

## 8. Plano de testes

**Matriz mínima:** Edge 50 Ultra ↔ S21 (root), nas duas direções.

Casos:

- 1 arquivo pequeno (1 KB), 1 médio (10 MB), 1 grande (4 GB);
- 200 arquivos de uma vez;
- transferência interrompida (tela apaga, app vai para background, Wi-Fi desliga) → retomada;
- Bluetooth desligado, Wi-Fi desligado, modo avião, economia de bateria, VPN ativa;
- hotspot ativo no emissor;
- 3+ aparelhos na mesma sala (não deve haver pareamento errado);
- recebimento negado (não deve gravar nada).

Métricas: tempo até aparecer, tempo até conectar, throughput médio, taxa de sucesso,
consumo de bateria por transferência.

---

## 9. O que não vamos fazer

- Decompilar e redistribuir o app da Motorola, o Quick Share Extension ou componentes do
  `mosey`. Além de juridicamente arriscado, não ajuda: o stub não tem protocolo.
- Prometer AirDrop no Android. Não é questão de esforço, é de rádio.
- Depender de nuvem, conta ou servidor.
- Receber arquivo sem confirmação do usuário.

---

## 10. Decisões

**Fechadas:**

1. Escopo: **Android apenas**, sem Apple.
2. Fase 1: **só EasyDrop ↔ EasyDrop**.
3. Licença: **GPL-3.0**.
4. Sem repositório e sem código até o plano ser aprovado.

**Pendentes (antes de criar o repositório):**

1. **Application ID** — sugestão `dev.leandro.easydrop` ou `br.com.easydrop.app`.
2. **Identidade visual** — nome do app já está fechado; falta ícone e paleta.
3. **Confirmar Fase 0** — rodar o app de diagnóstico nos dois aparelhos antes de escrever
   qualquer linha do produto.

---

## 11. Laboratório de protocolos (`:lab`)

App separado do diagnóstico, feito para testar um protocolo por vez. A tela inteira muda de
cor conforme o estado: cinza parado, azul procurando, âmbar peer encontrado, laranja
conectando, verde conectado, verde escuro transferindo, vermelho erro. Cada teste transfere
16 MB e mostra tempo de setup e MB/s.

### Modos

| Modo | Como identifica | Como conecta | Estado |
|---|---|---|---|
| LAN + mDNS | mDNS | TCP na rede comum | **funciona** |
| BLE + Hotspot | BLE (UUID + RSSI) | GATT entrega SSID/senha, `WifiNetworkSpecifier` entra | **funciona** |
| NFC + Hotspot | toque NFC (AID próprio) | APDU entrega SSID/senha, `WifiNetworkSpecifier` entra | **funciona** |
| Wi-Fi Direct | toque NFC entrega nome + SSID/senha | grupo P2P: receptor é dono, emissor entra | **funciona** |

### Medições (31/10/2026)

| Caminho | Setup | Throughput no receptor |
|---|---|---|
| LAN + mDNS | 1,5 s para conectar | 4,6 MB/s (limitado pelo AP) |
| BLE: anúncio → RSSI | 0,1–0,6 s | — |
| BLE: GATT com credenciais | 0,7–1,3 s | — |
| NFC: toque → credenciais | **0,02 s** | — |
| Hotspot: S21 hospeda, Edge recebe | 5–15 s | **12,7 MB/s** |
| Hotspot: S21 hospeda, Edge envia | 15 s | **6,0 MB/s** |
| Hotspot: Edge hospeda, S21 envia | 8 s | **13,0 MB/s** |
| Hotspot: Edge hospeda, S21 recebe | 5–47 s | **2,7 MB/s** |
| P2P: S21 dono, Edge envia (5 GHz) | toque → TCP **1,1–1,2 s** | **25,5–26,3 MB/s** |
| P2P: Edge dono, S21 envia (5 GHz) | toque → TCP **1,2 s** | **11,1–26,3 MB/s** |

O caminho antigo de P2P (descoberta + WPS) levava 3,8–4,8 s e rendia 5,0–14,5 MB/s. A conexão
rápida por SSID/senha tirou a descoberta do caminho crítico e estabilizou o setup em ~1,1 s.

### Wi-Fi Direct: de onde vinha o diálogo

O diálogo intermitente não era do `WifiNetworkSpecifier`; era do próprio P2P. No AOSP, quando o
dono do grupo recebe um *provision discovery* (`P2P_PROV_DISC_PBC_REQ_EVENT`), o
`WifiP2pServiceImpl` entra em `UserAuthorizingJoinState` e chama `notifyInvitationReceived()`,
que abre o diálogo de confirmação. O caminho antigo do laboratório usava exatamente isso:
`connect(deviceAddress)` com WPS PBC, sem SSID/senha. Às vezes o framework achava um grupo
persistente e pulava a descoberta; por isso o diálogo era intermitente.

A correção foi mudar o caminho:

- o receptor cria o grupo com SSID e senha conhecidos (`DIRECT-ED-xxxx` + senha aleatória) e
  publica **nome, SSID, senha e frequência** pelo NFC;
- o emissor chama `connect` com SSID/senha, sem endereço de peer. O framework reconhece
  `isConfigValidAsGroup()` e usa `p2pGroupAdd(config, true)`: entra no grupo direto, sem
  provision discovery, sem WPS e sem `UserAuthorizingJoinState`;
- a frequência publicada faz o emissor escanear só o canal do grupo (5745 MHz), em vez de
  varrer todas as bandas.

Resultado: 6 execuções (3 em cada direção), nenhum diálogo e toque → grupo em 1,0–1,1 s.

### Wi-Fi Direct: grupo velho e 5 GHz

- **Grupo velho sobrevive ao force-stop.** Depois de um teste interrompido, os dois aparelhos
  podiam ficar donos de grupos diferentes; o emissor via `isGroupOwner: true` e o TCP conectava
  nele mesmo (`ECONNREFUSED`). O `P2pCleanup` remove o grupo antes de iniciar e ao parar, com
  timeout de 2 s para o framework não travar o teste.
- **5 GHz no canal 149 (5745 MHz).** É não-DFS, então não passa por CAC. Se o aparelho não
  conseguir criar o grupo nessa frequência, cai para automático. O grupo real foi 5745 MHz em
  todas as execuções.
- **A frequência real só aparece depois do `createGroup()`.** O `requestGroupInfo()` é repetido
  a cada 500 ms até trazer a frequência; é ela que vai no payload NFC, não a pedida.
- **A variância da Edge como receptora é concorrência STA+P2P.** A STA da Edge está em 5 GHz
  (canal 36) e o grupo em 5745 MHz; o rádio divide tempo entre os dois e o receptor oscila entre
  11 e 26 MB/s. A STA do S21 está em 2,4 GHz, então o grupo em 5 GHz fica limpo e o S21 como
  receptor entrega 25,5–26,3 MB/s de forma estável. Não é diferença de Wi-Fi 6 para Wi-Fi 7.
- **Grupo em 2,4 GHz não resolve a concorrência.** Forçando o grupo em 2412 MHz com a Edge como
  receptora, a taxa caiu para 2,8–3,9 MB/s nas três execuções. O problema é a STA do **emissor**:
  o S21 envia com a STA em 2,4 GHz e passa a disputar a mesma banda com o grupo. A banda da STA
  do receptor não prevê o desempenho, e o receptor não a conhece antes do toque. Por isso o grupo
  fica fixo em 5745 MHz; a heurística pela banda do receptor foi descartada.
- **TCP agora tem ACK.** O emissor enviava 16 MB, o buffer do socket aceitava e ele anunciava
  "enviado" antes de o receptor drenar. Um byte de confirmação no fim faz o emissor esperar o
  receptor; as duas pontas passaram a reportar a mesma taxa. As duas esperas de 500 ms do
  `TcpLink` também saíram: toque → transferência concluída ficou em 1,8–2,6 s.

### O que foi descoberto no hotspot

**1. Quem recebe deve hospedar o hotspot.** O cliente de `WifiNetworkSpecifier` fica em
economia de energia e **recebe** devagar, mas **transmite** bem (229 Mbps de Tx contra 17 Mbps
de Rx com RSSI -10). Invertendo a hospedagem, o cliente só envia: o pior caso subiu de 2,7 para
6,0 MB/s e o melhor ficou em 13,0 MB/s.

**2. `removeCapability(NET_CAPABILITY_INTERNET)` era parte do problema.** Sem ele a rede é
tratada como "local-only", o que derruba o Rx para 17 Mbps. Removendo, o mesmo teste subiu de
0,3 para 2,0 MB/s. Os sockets são presos à rede explicitamente, então nada vaza para a internet.

**3. O gargalo não é TCP.** Em 16 MB houve 11.764 segmentos e apenas 3 retransmissões; janela de
4 MB não mudou a taxa. O limite é o rádio acordando e dormindo, não a janela.

**4. `WifiNetworkSuggestion` não funciona no S21.** `addNetworkSuggestions` devolve sucesso
(status 0), mas `cmd wifi list-suggestions` não lista nada e a rede nunca conecta.

**5. O setup do hotspot é dominado pelo diálogo do sistema.** `NetworkRequestDialogActivity`
("Conectar ao aparelho?") espera um toque; é ele que faz o setup variar de 5 a 47 s. O
`WifiLock` de baixa latência não desliga o power save nessa rede (`mPowerSaveDisableRequests 0`).

Consequência para a Fase 1: **Wi-Fi Direct como transporte principal** — sem confirmação manual,
~1,2 s de setup e ~25 MB/s no receptor quando o grupo não disputa a banda com a STA — e hotspot
local como reserva para aparelhos sem P2P. O toque NFC continua sendo o gatilho nos dois. O grupo
fica fixo em 5 GHz (canal 149): a banda que importa é a da STA do emissor, então não há como
escolher o canal pelo receptor antes do toque.


