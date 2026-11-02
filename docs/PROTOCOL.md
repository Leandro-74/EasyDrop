# Protocolo EasyDrop v1

> Status: especificação da Fase 1. Identificador de fio: `easydrop/1`.
> O identificador de fio é estável e não muda com o nome comercial do aplicativo.

## 1. Escopo

Este documento define o contrato entre emissor e receptor:

1. **Descoberta** — payload entregue por NFC (HCE).
2. **Handshake** — Noise NK sobre TCP.
3. **Transporte** — frames cifrados com prefixo de tamanho.
4. **Mensagens** — oferta, aceite, dados, fim, confirmação, cancelamento e erro.

O protocolo não depende de Android. A implementação de referência fica em `:protocol`
(JVM puro) e é testada sem aparelho.

## 2. Convenções

- Inteiros em **big-endian**.
- Strings em **UTF-8**, com prefixo de tamanho em bytes (não em caracteres).
- `u8`, `u16`, `u32`, `u64` são inteiros sem sinal.
- Todo decoder valida tamanho, versão e limites antes de alocar.
- Um frame malformado encerra a sessão com `Error` e fecha o socket.

## 3. Descoberta (NFC)

O receptor emula um cartão HCE com um AID próprio. A resposta a `GET DATA` é o payload
abaixo, seguido do status word `0x9000`.

```
offset  size  campo
0       1     version = 1
1       1     flags
2       1     nameLen (0..64)
3       n     name (UTF-8)
...     1     ssidLen (0..32)
...     n     ssid (UTF-8)
...     1     passLen (0..63)
...     n     pass (UTF-8)
...     2     frequency (MHz, 0 = desconhecido)
...     32    noisePublicKey (X25519)
```

### 3.1 Flags

| Bits | Campo | Valores |
|---|---|---|
| 0–1 | transporte | 1 = P2P, 2 = hotspot, 3 = LAN |
| 2 | credenciais presentes | 0/1 |
| 3 | chave Noise presente | 0/1 |
| 4–7 | reservado | 0 |

Para o MVP, `flags = 0b00000011` (P2P + credenciais + chave).

### 3.2 Limites

- Tamanho máximo: `2 + (1+64) + (1+32) + (1+63) + 2 + 32 = 198` bytes.
- Com o status word, a resposta APDU fica em 200 bytes, dentro do limite de APDU curta
  (~255 bytes). O limite do HCE é o C20; se o payload crescer, será preciso chunking ou
  APDU estendida, não um codec diferente.

### 3.3 Regras

- `version != 1` → o emissor aborta com "receptor incompatível".
- `nameLen == 0` é inválido.
- `ssidLen == 0` ou `passLen == 0` só é válido se o bit 2 estiver em 0.
- `frequency == 0` significa "desconhecida"; o emissor cai na descoberta por peer.
- Newlines em `name`, `ssid` e `pass` são permitidos: o formato é binário e não usa
  delimitador de linha (resolve C21).
- A chave Noise é a chave estática **efêmera** do receptor para aquela janela de recebimento.

## 4. Handshake (Noise)

- Protocolo: `Noise_NK_25519_ChaChaPoly_SHA256`.
- Prólogo: `easydrop/1` (ASCII, sem NUL).
- O emissor é o **iniciador**; o receptor é o **respondente**.
- O receptor gera um par de chaves X25519 **por janela de recebimento** e publica a chave
  pública no payload NFC.
- O emissor usa a chave pública do NFC como chave estática remota. Um atacante que não
  leu o NFC não consegue completar o handshake.

Mensagens:

```
M1 (emissor → receptor): e, es       48 bytes
M2 (receptor → emissor): e, ee       48 bytes
```

Depois de M2, os dois lados chamam `Split()` e passam a usar ChaCha20-Poly1305.

### 4.1 Por que NK e não XX

NK tem 1 RTT e autentica o **receptor** para o emissor. O emissor não tem identidade
persistente: a autorização dele é a posse do payload NFC (credenciais + chave), que só
é obtida com contato físico. XX adicionaria uma terceira mensagem e uma identidade de
emissor que o MVP não usa. Se houver necessidade de identidade mútua ou de dispositivos
confiáveis, o protocolo sobe para v2 com XX.

### 4.2 Janela de recebimento

- A janela abre quando o receptor arma o modo "receber" e fecha por timeout (padrão 120 s)
  ou após uma sessão concluída.
- Ao fechar, o receptor limpa o payload NFC, derruba o grupo P2P e descarta as chaves.
- Uma janela aceita **uma** sessão. Uma segunda conexão é recusada.
- O payload NFC só é válido enquanto a janela está aberta.

### 4.3 Limites do Noise

- Pacote Noise máximo: 65535 bytes.
- Tamanho máximo de plaintext por frame: 65519 bytes.
- O MVP usa payload de dados de até 32 KiB por frame.

## 5. Transporte

Cada frame é enviado como:

```
u32 ciphertextLength (1..65535)
u8[ciphertextLength] ciphertext
```

`ciphertext = Noise.encrypt(plaintextFrame)`, incluindo o tag de 16 bytes.

O prefixo de tamanho **não** é cifrado. Isso revela apenas o tamanho de cada frame; o
conteúdo, o tipo e os metadados ficam cifrados. Não há padding no v1.

Plaintext:

```
u8 type
u8 flags (0 no v1)
payload...
```

## 6. Mensagens

### 6.1 Offer (0x01)

```
u16 fileCount (1..1024)
repete fileCount vezes:
  u16 nameLen (1..255)
  name (UTF-8)
  u64 size
  u16 mimeLen (0..127)
  mime (UTF-8)
```

`size` é o tamanho exato do arquivo. O receptor rejeita `size` acima do limite local
(padrão 10 GiB) ou acima do espaço livre.

### 6.2 Accept (0x02)

```
u16 fileIndex
u64 startOffset (0 no v1)
```

`startOffset` reserva a retomada. No v1 o receptor sempre envia 0 e rejeita `Data` com
offset diferente do esperado.

### 6.3 Reject (0x03)

```
u8 reason
u16 messageLen (0..512)
message (UTF-8)
```

Motivos: 1 = usuário, 2 = não suportado, 3 = ocupado, 4 = sem espaço, 5 = inválido.

### 6.4 Data (0x04)

```
u16 fileIndex
u64 offset
u32 dataLen (1..32768)
data
```

### 6.5 End (0x05)

```
u16 fileIndex
u8[32] sha256
```

O SHA-256 cobre o conteúdo completo do arquivo. O receptor calcula enquanto grava e
compara. Divergência → apaga o arquivo parcial e responde `Ack` com status 1.

### 6.6 Ack (0x06)

```
u16 fileIndex
u8 status (0 = ok, 1 = hash divergente, 2 = erro de escrita, 3 = cancelado)
```

### 6.7 Cancel (0x07)

```
u8 reason (1 = usuário, 2 = timeout, 3 = erro)
```

Pode ser enviado por qualquer lado. O receptor apaga o arquivo parcial.

### 6.8 Error (0x08)

```
u16 code
u16 messageLen (0..512)
message (UTF-8)
```

Códigos:

| Código | Significado |
|---|---|
| 0x0001 | versão inválida |
| 0x0002 | frame inválido |
| 0x0003 | frame inesperado |
| 0x0004 | arquivo grande demais |
| 0x0005 | sem espaço |
| 0x0006 | hash divergente |
| 0x0007 | erro de escrita |
| 0x0008 | cancelado |
| 0x0009 | timeout |
| 0x000A | erro interno |

Tipos 0x09–0xFF são reservados. Um tipo desconhecido gera `Error 0x0002` e fecha.

## 7. Fluxo

```
receptor: abre janela (grupo P2P + chaves + NFC + servidor TCP)
emissor:  lê NFC, entra no grupo, conecta TCP
ambos:    Noise NK (M1, M2, Split)
emissor:  Offer
receptor: valida metadados e envia Accept (startOffset = 0)
emissor:  Data*
emissor:  End(sha256)
receptor: grava, confere hash, envia Ack
repete para cada arquivo
ambos:    fecham a sessão
receptor: fecha a janela
```

No MVP o receptor **aceita automaticamente** quando o handshake conclui. Não há diálogo
de aceite. A segurança vem do contato físico: sem ler o NFC, o emissor não tem a chave
nem as credenciais. O usuário pode cancelar pela notificação.

## 8. Compatibilidade

- O receptor aceita apenas a mesma versão maior (`easydrop/1`).
- O prólogo do Noise já vincula a versão: versões diferentes falham no handshake com
  mensagem clara, sem tentar interpretar bytes desconhecidos.
- Campos novos devem ser adicionados em uma versão nova, não como sufixo ignorado.
- O decoder v1 rejeita bytes sobrando no fim de um frame.

## 9. Invariantes de segurança

1. Nenhum byte de arquivo ou metadado trafega fora do Noise.
2. A chave pública do receptor vem do NFC e é a única aceita pelo emissor.
3. As chaves são efêmeras por janela; não há chave de sessão persistida.
4. O receptor só aceita uma sessão por janela.
5. O nome do arquivo é sanitizado antes de tocar no MediaStore.
6. O hash é verificado antes de o arquivo ser considerado concluído.

## 10. Pendências

- Chunking do payload NFC se ele passar de 255 bytes (C20).
- Retomada por offset (campo reservado; implementação futura).
- Múltiplos emissores simultâneos (fora do MVP).
- Padding de frames para reduzir análise de tráfego (fora do MVP).
