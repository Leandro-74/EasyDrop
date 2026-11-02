# Segurança

> Modelo de ameaças do MVP. O que não está aqui não é promessa de segurança.

## 1. Decisão

- Handshake: **Noise NK** (`Noise_NK_25519_ChaChaPoly_SHA256`).
- Implementação: `org.signal.forks:noise-java:0.1.1` (MIT, fork mantido pelo Signal,
  Java puro, sem JNI). Não é uma biblioteca auditada; a mitigação é testar contra
  vetores oficiais e manter a superfície pequena.
- OOB: a chave pública efêmera do receptor vai no payload NFC.
- Transporte: ChaCha20-Poly1305, frames com prefixo de tamanho.
- BLE: fora do produto. Permanece no laboratório.

### 1.1 Por que Noise

Noise NK tem 1 RTT, não carrega certificado, não depende de PKI e cabe em um handshake
de 96 bytes. É mais leve que TLS para este caso e não exige uma cadeia de confiança
externa: a confiança vem do toque NFC.

### 1.2 O que o Noise não resolve

- **Identidade do emissor.** NK autentica o receptor. O emissor é autorizado pela posse
  do payload NFC, não por uma identidade persistente.
- **Confidencialidade do tamanho.** O prefixo de tamanho do frame é visível.
- **Disponibilidade.** Um atacante na mesma rede pode tentar abrir conexões e consumir
  recursos; o receptor aceita uma sessão por janela e fecha por timeout.
- **Conteúdo malicioso.** O app não é antivírus. O hash garante integridade, não
  segurança do arquivo.

## 2. Modelo de ameaças

| Ameaça | Cenário | Mitigação |
|---|---|---|
| Escuta passiva | Alguém no mesmo canal lê o tráfego | Noise ChaCha20-Poly1305 |
| MITM | Atacante intercepta e troca chaves | Chave do receptor fixada pelo NFC; handshake falha |
| Emissor não autorizado | Aparelho na rede sem tocar no receptor | Sem payload NFC não há chave nem credenciais |
| Replay | Repetir um payload NFC capturado | Chaves e credenciais rotacionadas por janela; janela curta |
| Path traversal | Nome `../../etc/passwd` | Sanitização antes do MediaStore |
| Nome malicioso | NUL, controle, 500 caracteres | Whitelist, limite e truncamento |
| Arquivo gigante | `size` mentiroso ou 1 TB | Limite local + espaço livre antes do Accept |
| Hash divergente | Corrupção ou adulteração | SHA-256 no `End`; arquivo parcial apagado |
| Segunda sessão | Outro aparelho tenta entrar | Uma sessão por janela; fecha após concluir |
| DoS simples | Muitas conexões | Timeout de handshake, uma sessão, fecha a janela |

## 3. Aceite automático

O receptor aceita sem diálogo quando **todas** as condições valem:

1. A janela de recebimento está aberta.
2. O handshake Noise NK concluiu com a chave publicada no NFC.
3. É a primeira sessão da janela.
4. Os metadados passam na validação (nome, tamanho, espaço).
5. O hash do arquivo confere no `End`.

A justificativa é física: para completar o handshake, o emissor precisou ler o NFC do
receptor. Quem não encostou não tem a chave nem as credenciais. O usuário continua com
um botão **Cancelar** na notificação.

Consequência explícita: **quem consegue encostar no receptor pode enviar um arquivo**.
Isso é o comportamento desejado, equivalente a "receber de qualquer pessoa próxima",
mas com prova de toque. Não é adequado para receber de desconhecidos em local público
sem supervisão.

## 4. Ciclo de vida das chaves

- Par X25519 gerado a cada janela de recebimento.
- Chave pública no payload NFC; chave privada só na memória do receptor.
- Nada de chave de sessão em disco.
- Ao fechar a janela: limpar payload, descartar chaves, derrubar o grupo.
- Sem "dispositivos confiáveis" no MVP. Se entrar, guardar apenas hash de chave pública
  no Keystore, nunca a chave privada.

## 5. BLE

O modo BLE do laboratório publica SSID e senha no GATT. Isso é aceitável apenas em
laboratório. Para o produto:

- BLE não entra no MVP.
- Se entrar, faz só descoberta; credenciais e chave são trocadas após handshake.
- Precisa de SAS ou outro OOB, porque BLE não prova toque.

## 6. Pendências

- Vetores oficiais do Noise no CI.
- Teste de MITM com um proxy real entre os dois aparelhos.
- Revisão de dependências e licenças (`THIRD_PARTY_NOTICES.md`).
- Política de retenção de logs (não logar SSID, senha, nome de arquivo ou chave).
- Revisão do `allowBackup` e das regras de extração de dados.
