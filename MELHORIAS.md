# EasyDrop — Análise e propostas de evolução

> **01/11/2026 · documento de planejamento.** Nenhum código foi alterado.
> Objetivo: listar correções, otimizações e próximos passos para testar antes de decidir o que
> vale implementar. Cada item tem uma forma de medir e um critério de aceitação.

---

## 1. Leitura rápida

O laboratório já provou o caminho principal: o NFC entrega as credenciais, o receptor cria o
grupo Wi-Fi Direct em 5745 MHz, o emissor entra direto com SSID/senha e o TCP sustenta
~25 MB/s com setup de ~1,1 s. O que falta não é "fazer funcionar"; é transformar o experimento
em produto e corrigir alguns vícios de laboratório que podem estar distorcendo as medições.

Prioridades, em ordem:

1. **Medir melhor antes de otimizar.** Criar um harness de benchmark e corrigir o `setupMs`,
   que hoje mostra o tempo total no lugar do tempo de conexão.
2. **Consertar o `WifiLock`.** Falta a permissão `WAKE_LOCK`; o lock provavelmente nunca foi
   adquirido e o log diz que está ativo. Isso pode mudar a conclusão sobre o hotspot.
3. **Cancelamento de verdade.** `Parar` durante a transferência não fecha o socket ativo; a
   thread e o rádio continuam trabalhando.
4. **Unificar o caminho de transferência.** LAN e P2P usam códigos diferentes; um tem ACK, o
   outro não, e os números não são comparáveis.
5. **Começar o MVP real.** Arquivo de verdade, share sheet, MediaStore, hash, progresso e
   cancelamento — o laboratório de 16 MB já cumpriu o papel dele.
6. **Criptografia antes de expor para fora.** O lab transfere texto claro; o produto precisa de
   handshake, chave de sessão e confirmação.

O resto do documento detalha cada ponto, com esforço, risco e como testar.

---

## 2. O que já está sólido

- **Separação de responsabilidades no lab.** `NfcBeam`, `TcpLink`, `HotspotLink`,
  `P2pCleanup` e os modos têm papéis claros; o mesmo toque NFC serve a transportes diferentes.
- **Caminho rápido do P2P bem documentado.** A escolha de SSID/senha em vez de `connect` por
  endereço + WPS eliminou o `UserAuthorizingJoinState` e o diálogo intermitente.
- **Grupo em 5 GHz fixo com fallback.** 5745 MHz é não-DFS e o código cai para automático se o
  aparelho recusar.
- **ACK no TCP.** O emissor espera o receptor drenar os 16 MB; as duas pontas reportam a mesma
  taxa.
- **Limpeza de grupo velho.** `P2pCleanup` resolve o caso de grupo sobrevivente ao force-stop.
- **Teste de regressão do `P2pConfig`.** Pequeno, mas protege a configuração que evita o
  diálogo.
- **Log e estado observáveis.** `LabLog` + `StateFlow` + cores por fase tornam o teste por adb
  simples e repetível.

---

## 3. Correções e riscos

### 3.1 Prioridade alta

#### C1. `WifiLock` sem `WAKE_LOCK` — o lock nunca foi adquirido

**Onde:** `lab/src/main/AndroidManifest.xml` (falta a permissão) e
`HotspotLink.kt` (`acquireWifiLock`).

**Problema:** qualquer uso de `WifiManager.WifiLock` exige
`android.permission.WAKE_LOCK`. O manifesto do lab não declara essa permissão. O código faz
`runCatching { acquire() }` e, em seguida, registra "Wi-Fi: lock de baixa latência ativo"
independentemente do resultado. Ou seja: a falha é engolida e o log mente.

**Por que importa:** o `PLANO.md` concluiu que o lock de baixa latência não desliga o power
save porque `mPowerSaveDisableRequests` estava em 0. Se o lock nunca foi adquirido, essa
conclusão pode estar errada. O caminho de hotspot pode render mais do que os 12,7 MB/s
medidos, e a decisão "P2P principal, hotspot reserva" merece ser reavaliada com o lock
funcionando.

**Correção proposta:**

- declarar `<uses-permission android:name="android.permission.WAKE_LOCK" />`;
- trocar o `runCatching` silencioso por algo que registre sucesso ou falha;
- opcionalmente, adquirir o lock também no receptor durante a transferência.

**Como testar:** rodar o hotspot nas duas direções, 3 vezes cada, antes e depois; conferir
`adb shell dumpsys wifi | grep -i -E "mPowerSaveDisableRequests|WifiLock"`; comparar MB/s.
Critério: se a taxa subir de forma consistente (>15%), atualizar o `PLANO.md` e reabrir a
decisão de transporte. Se não mudar, o lock não era o gargalo.

**Esforço:** baixo. **Risco:** baixo.

#### C2. `stop()` não interrompe a transferência em andamento

**Onde:** `TcpLink.kt` (`stop`), `LanMode.kt` (`stop`), e por consequência `HotspotLink`,
`BleMode`, `NfcMode` e `WifiDirectMode`.

**Problema:** `finished` só é checado entre fases. Durante `read`, `write`, `accept` ou a
espera do ACK, a thread fica bloqueada. `pool.shutdownNow()` interrompe a thread, mas I/O de
socket não responde a `interrupt`; `server.close()` libera o `accept`, mas não o socket já
aceito. O socket ativo não é guardado em campo nenhum, então `stop()` não consegue fechá-lo.

**Sintoma:** apertar "Parar" no meio da transferência deixa a thread e o rádio trabalhando; ao
reiniciar, o teste pode encontrar porta ocupada, conexão antiga ou grupo velho. Em um produto,
isso é pior: o usuário cancela e o arquivo continua sendo enviado/recebido.

**Correção proposta:**

- guardar o socket ativo (`@Volatile private var active: Socket?`);
- em `stop()`, fechar o socket ativo além do `ServerSocket`;
- usar `soTimeout` curto (ex.: 1 s) e checar `finished` nos loops;
- no `send`, usar timeout na leitura do ACK; no `receive`, timeout de inatividade.

**Como testar:** iniciar uma transferência, chamar `stop` após 1–2 s, reiniciar imediatamente;
repetir 5 vezes. Critério: nenhuma thread presa, nenhum "Address already in use", nova execução
funciona e o tempo de parada fica abaixo de 1 s.

**Esforço:** baixo/médio. **Risco:** baixo.

#### C3. Leitura do ACK sem timeout

**Onde:** `TcpLink.kt`, `send()`: `socket.getInputStream().read()` depois do envio.

**Problema:** se o receptor cair, travar ou fechar sem enviar o ACK, o emissor fica bloqueado
para sempre. O `Socket` não tem `soTimeout` para essa leitura.

**Correção proposta:** `socket.soTimeout = 10_000` antes do ACK; em timeout, falhar com
mensagem clara ("receptor não confirmou em 10 s").

**Como testar:** teste de loopback em que o "receptor" aceita a conexão, lê os 16 MB e não
responde; o emissor deve falhar em ~10 s, não travar.

**Esforço:** baixo. **Risco:** baixo.

#### C4. `setupMs` final é o tempo total, não o setup

**Onde:** `TcpLink.kt` (status final de `serve`/`send`) e `LanMode.kt` (status final de
`sendPayload`/`receivePayload`).

**Problema:** `startedAt` é marcado no início do servidor/envio; o status final usa
`System.currentTimeMillis() - startedAt`, que inclui toda a transferência. A UI mostra esse
valor ao lado do MB/s, então depois de uma transferência de 4 s o "setup" exibido é ~4 s.

**Correção proposta:** guardar `setupMs` no momento da conexão e reutilizá-lo no status final;
separar `setupMs` de `totalMs` no `LabStatus`.

**Como testar:** teste unitário com relógio falso; conferir no aparelho que o valor final é o
mesmo "TCP: conectado em Xms" do log.

**Esforço:** baixo. **Risco:** baixo.

### 3.2 Prioridade média

#### C5. LAN e P2P medem coisas diferentes

**Onde:** `LanMode.kt` vs `TcpLink.kt`; `app/src/main/java/dev/leandro/easydrop/diag/LanTransfer.kt`
é uma terceira cópia.

**Problema:** `LanMode` não tem ACK, usa buffers padrão, tem um `Thread.sleep(500)` e calcula o
tempo de envio sem esperar o receptor drenar. `TcpLink` tem ACK, buffers de 4 MB e nenhum
sleep. Os números de LAN e P2P não são comparáveis, e o `LanTransfer` do diagnóstico repete a
mesma lógica com outro tamanho de payload (32 MB).

**Correção proposta:** um único `Transfer` (socket + ACK + medição + cancelamento), usado por
LAN, hotspot e P2P. O `LanMode` passa a só descobrir e conectar; o `TcpLink` vira o transporte
comum. O `LanTransfer` do diagnóstico pode ser removido ou passar a usar o mesmo código.

**Como testar:** rodar LAN 3 vezes em cada direção antes/depois; comparar taxa do emissor e do
receptor. Critério: as duas pontas ficam dentro de ~5% e o tempo de conclusão não piora.

**Esforço:** médio. **Risco:** baixo.

#### C6. Chips de modo/papel habilitados após erro, mas sem efeito

**Onde:** `LabController.kt` (`selectMode`/`selectRole` só aceitam `Phase.IDLE`) e
`MainActivity.kt` (`enabled = !running`, e `ERROR` não conta como "running").

**Problema:** depois de um erro, os chips parecem clicáveis, mas o clique é ignorado. O usuário
precisa descobrir sozinho que deve apertar "Parar" antes.

**Correção proposta:** aceitar seleção em `IDLE` ou `ERROR` (resetando o status para `IDLE`), ou
desabilitar os chips em `ERROR` de forma explícita.

**Como testar:** teste unitário do `LabController` com um status em `ERROR`; clicar em outro
modo deve refletir no estado.

**Esforço:** baixo. **Risco:** baixo.

#### C7. `MainActivity` não para o controlador ao ser destruída

**Onde:** `MainActivity.kt` — não há `onDestroy`.

**Problema:** sair do app (back) durante um teste deixa `Handler`, sockets, hotspot e grupo P2P
vivos; o `LabController` segura a `Activity`. Em rotação/multiwindow, o vazamento se repete.

**Correção proposta:** `onDestroy { controller.stop(quiet = true) }` ou mover o estado para um
`ViewModel`/serviço. Para o lab, o `onDestroy` resolve.

**Como testar:** iniciar P2P, apertar back, conferir `dumpsys wifip2p` e `ss -ltn` que o grupo e
a porta sumiram.

**Esforço:** baixo. **Risco:** baixo.

#### C8. Senha do hotspot no log

**Onde:** `HotspotLink.kt`: `LabLog.add("hotspot pronto ($ssid / $pass)")`.

**Problema:** a senha vai para o logcat e para a tela. No laboratório é aceitável; no produto,
não. O log também aparece em capturas de tela e relatórios de bug.

**Correção proposta:** registrar só o SSID e mascarar a senha (`***`); se precisar depurar,
usar `BuildConfig.DEBUG` e um canal separado.

**Como testar:** grep no logcat por "hotspot pronto" não deve conter a senha.

**Esforço:** baixo. **Risco:** baixo.

#### C9. Permissões pedidas em excesso no Android 13+

**Onde:** `MainActivity.kt` (`requiredPermissions`).

**Problema:** `ACCESS_FINE_LOCATION` é pedida mesmo em API 33+, onde P2P/BLE usam
`NEARBY_WIFI_DEVICES` e `BLUETOOTH_SCAN` com `neverForLocation`. Isso gera um diálogo de
localização desnecessário e assusta o usuário.

**Correção proposta:** pedir localização só em API < 33; em API 33+, pedir apenas as permissões
"nearby". Testar em instalação limpa.

**Como testar:** desinstalar, instalar, abrir e conferir quantos diálogos aparecem; o app deve
funcionar sem localização em API 35.

**Esforço:** baixo. **Risco:** baixo.

#### C10. `WifiDirectMode` exige NFC mesmo quando o P2P não precisa

**Onde:** `WifiDirectMode.start()` chama `beam.problem()` e falha se o NFC estiver desligado.

**Problema:** o Wi-Fi Direct funciona sem NFC; o lab só usa o toque como gatilho. Um aparelho
sem NFC (ou com NFC desligado) não consegue testar o P2P.

**Correção proposta:** permitir gatilho alternativo (BLE, QR ou botão "conectar ao último
peer") para o P2P. Para o produto, o NFC é o atalho premium, não o único caminho.

**Como testar:** desligar NFC, iniciar P2P pelo gatilho alternativo e medir o setup.

**Esforço:** médio. **Risco:** baixo.

#### C11. Grupo velho pode sobreviver se `removeGroup` falhar

**Onde:** `WifiDirectMode.stop()` e `LabController.stop()`.

**Problema:** `P2pCleanup` só é chamado quando não há `WifiDirectMode` em memória. Se o
`removeGroup` do modo falhar (framework ocupado), o grupo fica e a próxima execução pode
formar outro grupo.

**Correção proposta:** sempre agendar `P2pCleanup` como fallback depois do `stop`, com timeout;
ou repetir `removeGroup` com backoff em caso de `BUSY`.

**Como testar:** forçar um grupo e matar o app; reiniciar e conferir que só existe um grupo e
que o TCP conecta no dono certo.

**Esforço:** baixo/médio. **Risco:** baixo.

#### C12. Documentação desatualizada

**Onde:** `PLANO.md` e `README.md`.

**Problemas concretos:**

- `PLANO.md` ainda diz "Nada foi implementado ainda. Nenhum repositório foi criado."
- `PLANO.md` §5.3 diz que "o emissor sobe um LocalOnlyHotspot", mas a medição mostrou que quem
  recebe deve hospedar.
- `PLANO.md` §6 (Fase 1) trata Wi-Fi Direct como fallback, enquanto §11 o coloca como principal.
- `PLANO.md` §10 fala em "antes de criar o repositório", que já existe.
- `README.md` ainda mostra P2P com "3,8–4,8 s" e "5–14,5 MB/s", números do caminho antigo; o
  caminho rápido está em ~1,1 s e ~25 MB/s.
- `README.md` e `PLANO.md` não registram o experimento de 2,4 GHz (já documentado em §11, mas o
  README não aponta para ele).

**Correção proposta:** atualizar os dois documentos e adicionar um "Estado atual" com data,
números e link para este arquivo. É barato e evita decisões baseadas em dados velhos.

**Esforço:** baixo. **Risco:** nenhum.

### 3.3 Menores, mas vale anotar

| ID | Onde | Problema | Correção |
|---|---|---|---|
| C13 | `TcpLink.send` | `sendBufferSize`/`receiveBufferSize` fora de `runCatching`; pode lançar em aparelhos que limitam o buffer | envolver em `runCatching` como em `tune` |
| C14 | `TcpLink` | `server` e `startedAt` não são `@Volatile`; `stop` e a thread de transferência competem | `@Volatile`/`AtomicLong` |
| C15 | `LabScreen` | `LaunchedEffect(lines.size)` para de rolar quando o log bate em 400 linhas | usar a última linha ou um contador monotônico |
| C16 | `BleMode` | `onCharacteristicReadRequest` ignora `offset` e o MTU; payload maior que o MTU quebra | responder a partir do offset e truncar no MTU |
| C17 | `BleMode` | escolhe o primeiro anúncio acima do limiar, não o mais forte | janela de 1–2 s + escolher o maior RSSI |
| C18 | `LanMode` | `resolveService` concorrente pode dar `FAILURE_ALREADY_ACTIVE`; `Socket(host, port)` sem timeout | serializar resolves com retry; timeout de conexão |
| C19 | `NfcBeam.present` | não confere se o NFC continua ligado; falha silenciosa | checar `nfc.isEnabled` e reportar |
| C20 | `EasyDropApduService` | resposta HCE curta limita o payload a ~255 bytes; sem chunking | definir limite e, se crescer, usar APDU estendida/chunking |
| C21 | `NfcMode`/`WifiDirectMode`/`BleMode` | payload delimitado por `\n`; SSID/nome com newline quebra o parsing | codec binário/TLV com tamanho por campo (ver §4) |
| C22 | `LabLog` | cada linha copia a lista inteira (`it + line`); em rajada, aloca muito | `ArrayDeque` + snapshot ou lote de linhas |
| C23 | `P2pConfigTest` | o teste de frequência funciona porque a API 35 grava a frequência em `groupOwnerBand`; é frágil | manter, mas adicionar um teste de `toString`/reflexão e revisar ao subir o SDK |

---

## 4. Estrutura: o lab precisa usar o código do produto

Hoje existem **três** implementações de transferência:

- `lab/TcpLink.kt` (P2P e hotspot, com ACK);
- `lab/LanMode.kt` (LAN, sem ACK, com sleep);
- `app/diag/LanTransfer.kt` (diagnóstico, 32 MB, outra cópia).

E **três** parsers de credenciais:

- `BleMode` (`ssid\npass`);
- `NfcMode` (`ssid\npass`);
- `WifiDirectMode` (`name\nssid\npass\nfrequency`).

Isso significa que "o lab validou" não é o mesmo que "o produto vai se comportar assim". A
recomendação é extrair o núcleo compartilhado **quando o MVP começar**, sem criar dez módulos
agora:

```
:core:protocol   payload NFC/BLE, framing TCP, manifesto, hash (Kotlin puro, testável em JVM)
:core:transport  P2P, LAN, hotspot, transferência, cancelamento
:core:crypto     X25519/HKDF/AEAD/SAS (Tink)
:lab             UI de teste, consumindo :core:transport
:app             produto: share sheet, serviço, MediaStore, UI
```

Vantagens:

- o lab mede exatamente o código que o produto usa;
- um bug corrigido no transporte vale para todos os modos;
- os testes de protocolo rodam em JVM, sem aparelho;
- o `:lab` pode continuar sendo um app separado, mas fino.

Não vale a pena dividir tudo antes do MVP. Comece por `:core:protocol` (puro) e
`:core:transport` (P2P + TCP). O `:app` de diagnóstico pode ser aposentado ou virar
`:tools:diag` quando o produto nascer.

### Codec de payload

Proposta de formato binário versionado, em vez de linhas:

```
magic "EDP1" | versão (1 byte) | tipo (1 byte)
campo: tamanho (2 bytes) + UTF-8/bytes
...
```

Campos: `name`, `ssid`, `pass`, `frequency`, `token` (opcional, para o produto). Vantagens:
aceita SSID com caracteres arbitrários, tem limite explícito, permite evoluir sem quebrar o
outro lado e é trivial de testar. O mesmo codec serve NFC, BLE e QR.

### Framing TCP

Para o produto, o `TcpLink` de 16 MB fixos vira um protocolo com:

- `HELLO` (versão, capabilities);
- `MANIFEST` (quantidade de arquivos, nome, tamanho, MIME, SHA-256);
- `CHUNK` (offset/sequência + bytes);
- `ACK` (janela/offset);
- `DONE`/`ERROR`;
- retomada por offset e hash por arquivo.

Testar o codec em JVM com `PipedInputStream`/`PipedOutputStream` ou sockets de loopback.

---

## 5. Otimizações e experimentos

### 5.1 Primeiro, o harness

Sem uma medição repetível, cada otimização vira opinião. Proposta:

- o lab aceita `--es runId <id>` e `--es payloadMB <n>` e emite uma linha JSON por execução:
  `BENCH {"runId":...,"mode":"P2P","role":"SENDER","setupMs":...,"mbps":...,"groupMhz":...}`;
- um script `scripts/bench.sh` roda N iterações por direção, limpa o logcat, troca os papéis,
  coleta os JSONs e gera CSV com mediana e p95;
- o CSV guarda também `sta_band`, `group_mhz`, temperatura e versão do app;
- resultados ficam em `bench/results/` (resumos versionados; logs brutos ignorados).

Comandos úteis para acompanhar:

```bash
adb shell cmd wifi status
adb shell dumpsys wifip2p | grep -i -E "frequency|group|channel"
adb shell dumpsys wifi | grep -i -E "mPowerSaveDisableRequests|WifiLock"
adb shell dumpsys thermalservice | grep -i -E "status|temperature"
adb shell dumpsys batterystats --charged | grep -i easydrop
```

### 5.2 Tabela de experimentos

| ID | Hipótese | Como medir | Critério para aceitar | Esforço |
|---|---|---|---|---|
| E1 | Com `WAKE_LOCK`, o hotspot melhora e o lock realmente atua | 3 runs/direção antes/depois; `dumpsys wifi` | +15% de taxa ou `mPowerSaveDisableRequests` > 0 | S |
| E2 | Fechar o socket ativo torna o "Parar" imediato | parar no meio, reiniciar 5x | parada < 1 s, sem porta presa | S |
| E3 | Separar `setupMs` de `totalMs` corrige a leitura | teste com relógio falso + 3 runs | setup final = "conectado em Xms" | S |
| E4 | Unificar LAN no `TcpLink` iguala as pontas | 3 runs LAN/direção | emissor e receptor dentro de 5% | M |
| E5 | Broadcasts do P2P reduzem o setup vs polling de 500 ms | 10 runs/direção | mediana < 1,0 s e p95 melhor | M |
| E6 | Zero-copy (`FileChannel.transferTo`) ajuda em arquivo real | 1 GB sintético vs arquivo real | +10% sem regressão | M |
| E7 | Buffer de socket 8 MB melhora o throughput | 1/4/8 MB, 3 runs cada | +10% | S |
| E8 | 16 MB esconde throttling térmico | 4 GB contínuos, taxa a cada 10 s | taxa estável > 15 MB/s | M |
| E9 | Grupo P2P persistente reduz o setup | pré-criar grupo, 10 toques | setup < 0,5 s sem falhas | M |
| E10 | Dois fluxos TCP ajudam em muitos arquivos | 1 vs 2 vs 4 fluxos | +15% no total | M |
| E11 | Canal 36/44 rende mais que 149 no ambiente | 3 runs por canal | diferença > 10% | M |
| E12 | Escolher o peer mais forte evita conexão errada | 3 aparelhos, 20 runs | 0 conexões erradas | M |
| E13 | P2P sem NFC amplia o alcance | NFC desligado, gatilho BLE/QR | toque/BLE → TCP < 2 s | M |
| E14 | Receber em background funciona com foreground service | tela apagada, app em background | > 90% de sucesso | L |
| E15 | Criptografia custa pouco | handshake + 1 GB, AES-GCM vs ChaCha20 | < 100 ms de setup, sem queda de taxa | L |
| E16 | Um emissor serve dois receptores | 3 aparelhos, 1→2 | ambos concluem; medir queda | M |
| E17 | Target SDK 36/37 não quebra o app | subir e rodar a matriz | sem crash, permissões corretas | M |
| E18 | CI pega regressões | GitHub Actions com build + testes + lint | pipeline verde | S |
| E19 | Interop Quick Share é viável | spike com Bada/NearDrop | enviar para o Edge sem GMS | L |

### 5.3 Detalhes dos experimentos que eu faria primeiro

**E1 — `WAKE_LOCK` no hotspot.** É o de maior alavancagem porque pode invalidar uma conclusão
do plano. Rode o hotspot com a permissão e o log corrigido; veja se
`mPowerSaveDisableRequests` sai de 0 e se a taxa sobe. Se subir, o hotspot deixa de ser apenas
reserva e a decisão de transporte precisa ser revista com números novos.

**E5 — broadcasts no P2P.** Hoje o emissor faz `requestConnectionInfo` a cada 500 ms e
`requestPeers` a cada 500 ms. Um `BroadcastReceiver` para `WIFI_P2P_CONNECTION_CHANGED_ACTION`
e `WIFI_P2P_PEERS_CHANGED_ACTION` tende a reagir mais rápido e gastar menos. Meça o setup em 10
execuções; se a mediana cair 100–300 ms, vale.

**E6/E7/E8 — throughput real.** Os 25 MB/s atuais são com 16 MB e sem gravar em disco. O
número que importa é arquivo real de 1–4 GB, com gravação em `MediaStore`, tela apagada e
transferência contínua. Antes de otimizar, descubra o teto real. Teste `FileChannel.transferTo`,
buffer de 1 MB e socket de 8 MB; se nada mudar, o limite é o rádio ou o armazenamento, e não o
código.

**E9 — grupo persistente.** O setup de ~1,1 s é dominado pela formação do grupo. Manter o grupo
vivo entre transferências pode derrubar para <0,5 s, mas custa bateria, exposição e o risco de
grupo velho. Teste com um grupo pré-criado e 10 toques; decida com dados de bateria e falhas.

**E13 — P2P sem NFC.** O NFC é ótimo, mas não existe em todos os aparelhos e pode estar
desligado. Um gatilho BLE (anúncio + GATT) ou QR mantém o P2P utilizável. Meça o setup e a
taxa de acerto.

**E14 — background.** O lab mantém a tela ligada; o produto precisa receber com o app em
background e a tela apagada. Foreground service com tipo `connectedDevice`/`dataSync`,
notificação, `WifiLock` e testes com battery saver/Doze nos dois aparelhos. É o experimento
que mais expõe fragmentação de OEM.

**E15 — criptografia.** Antes de qualquer uso externo, handshake X25519 + HKDF + AEAD + SAS.
Meça o custo no setup e no throughput; se o AEAD custar >10%, teste AES-GCM com aceleração de
hardware contra ChaCha20-Poly1305. Não invente criptografia: use Tink (Apache 2.0, compatível
com GPL-3.0).

---

## 6. Próximos passos de produto

### 6.1 Ordem recomendada

1. **Harness + correções baratas** (C1–C9, C12). Uma a duas semanas de trabalho, mas destrava
   todas as decisões seguintes.
2. **Protocolo + codec + testes** (`:core:protocol`). Payload versionado, framing, manifesto,
   hash. Sem UI.
3. **MVP de arquivo real.** Share sheet → P2P → `MediaStore/Downloads/EasyDrop`, progresso,
   cancelamento, hash, nome duplicado. Primeiro marco que um humano consegue usar.
4. **Criptografia + SAS.** Handshake, chave de sessão, confirmação; NFC como prova de presença.
5. **Foreground service + background.** Receber com tela apagada; notificação; cancelar;
   sobreviver a Doze/battery saver.
6. **Proximidade e atalhos.** Card por RSSI com histerese, NFC tap, QR fallback, tile de
   configurações rápidas.
7. **Performance e robustez.** Zero-copy, 4 GB, retomada, multi-arquivo, multi-receptor.
8. **Polimento e distribuição.** Ícone, tema, acessibilidade, assinatura, R8, CI, F-Droid.

### 6.2 MVP — o que entra e o que fica de fora

**Entra:**

- compartilhar 1..N arquivos pelo share sheet do Android;
- receber via P2P (receptor hospeda), com o toque NFC como gatilho;
- progresso, cancelar, erro claro;
- gravar em `Downloads/EasyDrop/` via MediaStore, com `.part` e rename no fim;
- SHA-256 por arquivo e verificação no receptor;
- tela de recebimento com "Aceitar/Recusar" (a confirmação explícita continua obrigatória).

**Fica de fora do primeiro MVP:**

- hotspot como fallback (só depois de E1);
- LAN/mDNS;
- QR;
- múltiplos receptores;
- retomada entre reinícios;
- Quick Share;
- UWB/Aware.

### 6.3 Segurança — desenho mínimo

- X25519 efêmero dos dois lados; HKDF-SHA256 para derivar a chave de sessão;
- AEAD (AES-GCM ou ChaCha20-Poly1305) em todos os frames;
- SAS de 6 dígitos derivado do transcript; os dois lados comparam;
- NFC carrega um token de uso único que entra no HKDF (prova de presença física);
- grupo P2P com senha aleatória por sessão; fechar o grupo depois da transferência;
- nunca logar senha, token ou chave;
- ameaças: eavesdropper passivo, MITM ativo, replay, dispositivo malicioso próximo. WPA2 do P2P
  protege o link, mas não substitui a autenticação da aplicação.

### 6.4 Background — o que testar

- foreground service com `connectedDevice` + `dataSync` e notificação;
- permissões `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`;
- `WifiLock` (com `WAKE_LOCK`) durante a transferência;
- tela apagada, app em background, battery saver, Doze, restrições de OEM;
- cancelamento pela notificação;
- processo morto no meio: o que acontece com o `.part` e com o grupo P2P.

### 6.5 Proximidade — o que testar

- RSSI BLE com média móvel e histerese (mostra em > −60 dBm, esconde em < −70 dBm);
- distâncias de 0,2 / 1 / 3 / 10 m, com o aparelho no bolso e na mão;
- múltiplos aparelhos: ordenar por RSSI e nunca conectar no primeiro que aparecer;
- NFC: posição da antena varia muito; mostrar dica visual e ter QR como plano B;
- UWB no Edge é bônus, não base (o S21 não tem);
- Wi-Fi Aware no S21 é bônus, não base (o Edge não expõe).

### 6.6 Performance — o que medir de verdade

- 1 KB, 10 MB, 1 GB e 4 GB; 1 e 200 arquivos; pasta;
- throughput no emissor e no receptor, setup, total, CPU, bateria e temperatura;
- gravação em `MediaStore` vs arquivo direto;
- tela apagada e app em background;
- cancelar no meio e retomar;
- Wi-Fi STA em 2,4 GHz e 5 GHz nos dois aparelhos;
- canal do grupo (149 vs 36/44) e largura de banda;
- 1 vs 2 vs 4 fluxos TCP;
- hash no fim: quanto custa em 4 GB.

### 6.7 Distribuição

- assinatura de release com keystore fora do repositório;
- R8/minify e teste do APK de release;
- CI com build + testes + lint;
- F-Droid é um bom alvo (GPL-3.0, sem GMS, sem nuvem);
- política de privacidade curta: nada sai do aparelho;
- avisos de licença de terceiros (Tink etc.);
- ícone, tema, acessibilidade e textos em pt-BR/en.

---

## 7. Ideias interessantes para depois

- **Token de uso único no NFC.** Em vez de publicar SSID/senha estáticos, publicar um token que
  o receptor valida e queima. O toque vira uma chave física de sessão.
- **Dispositivos confiáveis.** Guardar a chave pública do outro aparelho no Keystore e pular a
  confirmação nas próximas transferências, mantendo a criptografia.
- **Receber sem abrir o app.** Tile de configurações rápidas + foreground service + HCE; o
  usuário só encosta e o card aparece.
- **Share sheet direto.** `ACTION_SEND`/`ACTION_SEND_MULTIPLE` com `ClipData` e permissões
  temporárias; o EasyDrop aparece como destino sem abrir o app.
- **Um para muitos.** O grupo P2P aceita mais de um cliente; testar 1 emissor → 2 receptores,
  com taxa por receptor.
- **Retomada de verdade.** Manifesto + offset persistido; se o app morrer, continua do ponto
  onde parou.
- **Texto/links pelo BLE.** Para payloads pequenos, nem precisa subir Wi-Fi; útil para
  clipboard e links.
- **QR como fallback universal.** Quando NFC/BLE falham, o receptor mostra um QR com o token;
  o emissor lê e entra.
- **Canal do grupo inteligente.** Medir ocupação dos canais 5 GHz e escolher o menos congestionado
  (exige localização; testar o custo de UX).
- **UWB no Edge.** "Apontar e compartilhar" com ranging de centímetros; só como upgrade.
- **Wi-Fi Aware no S21.** Descoberta + ranging sem infraestrutura; só como bônus quando os dois
  aparelhos suportarem.
- **Histórico com retry.** Lista de transferências, reenviar, abrir pasta, compartilhar de novo.
- **Modo "só receber".** Deixar o aparelho visível por X minutos, com notificação e botão de
  parar.

---

## 8. O que não fazer agora

- **Não dividir o projeto em muitos módulos.** `:core:protocol` e `:core:transport` bastam no
  começo; o resto pode esperar.
- **Não adotar Hilt/Koin.** Injeção manual continua suficiente para o tamanho atual.
- **Não implementar Quick Share antes do MVP.** É a Fase 3 por um motivo: alto esforço e
  protocolo de terceiros.
- **Não investir em UWB/Aware como base.** O hardware dos dois aparelhos não fecha.
- **Não otimizar o transfer de 16 MB sintético além do necessário.** O número que importa é
  arquivo real gravado em disco.
- **Não usar criptografia caseira.** Tink ou uma biblioteca auditada.
- **Não prometer AirDrop.** Já está fora de escopo por hardware e produto.
- **Não manter três cópias da transferência.** Unifique antes de crescer.

---

## 9. Critérios de decisão

Para cada experimento, registrar:

| Campo | Exemplo |
|---|---|
| ID | E1 |
| Hipótese | com `WAKE_LOCK`, o hotspot passa de 12,7 para >15 MB/s |
| Mudança | permissão + log de falha |
| Medição | 3 runs/direção, `dumpsys wifi`, MB/s |
| Critério | +15% ou `mPowerSaveDisableRequests` > 0 |
| Resultado | (preencher) |
| Decisão | aceitar / rejeitar / repetir |
| Commit | (se aceitar) |

Manter os rejeitados documentados (como o grupo em 2,4 GHz) evita repetir a mesma experiência.

---

## 10. Arquivos de referência

- `lab/src/main/java/dev/leandro/easydrop/lab/HotspotLink.kt` — `WifiLock`, hotspot, WNS.
- `lab/src/main/java/dev/leandro/easydrop/lab/TcpLink.kt` — transferência, ACK, buffers.
- `lab/src/main/java/dev/leandro/easydrop/lab/LanMode.kt` — mDNS + TCP (caminho separado).
- `lab/src/main/java/dev/leandro/easydrop/lab/WifiDirectMode.kt` — grupo, NFC, descoberta.
- `lab/src/main/java/dev/leandro/easydrop/lab/P2pConfig.kt` — SSID/senha/frequência.
- `lab/src/main/java/dev/leandro/easydrop/lab/P2pCleanup.kt` — grupo velho.
- `lab/src/main/java/dev/leandro/easydrop/lab/BleMode.kt` — BLE + GATT + hotspot.
- `lab/src/main/java/dev/leandro/easydrop/lab/NfcBeam.kt` — HCE + reader mode.
- `lab/src/main/java/dev/leandro/easydrop/lab/LabController.kt` — estados e modos.
- `lab/src/main/java/dev/leandro/easydrop/lab/MainActivity.kt` — UI, permissões, adb.
- `app/src/main/java/dev/leandro/easydrop/diag/LanTransfer.kt` — terceira cópia da transferência.
- `PLANO.md` — plano técnico e medições.
- `README.md` — visão do produto e comandos.
