# Nome do projeto

> Decisão provisória: **QuicklyDrop**. Ainda não é definitiva.

## 1. Situação

`EasyDrop` conflita com um projeto existente no PyPI/GitHub
(`TheLastGimbus/easydrop`, CLI para AirDrop no Linux). O conflito é de busca e de
identidade, não de código: o `applicationId` Android é reverse-DNS e não colide.

`QuicklyDrop` é o candidato atual. A decisão final fica para antes do primeiro release
público (F-Droid/Play), não bloqueia o desenvolvimento.

## 2. O que verificar antes de fechar

- PyPI, npm, GitHub, F-Droid, Google Play e registro de domínio.
- Marca e nome de pacote no F-Droid.
- Disponibilidade de `dev.leandro.quicklydrop` (ou outro reverse-DNS).
- Conflito com "Quick Drop" (Samsung) e "Quick Share" (Google) na busca.

## 3. O que não muda agora

- `applicationId` atual: `dev.leandro.easydrop` / `dev.leandro.easydrop.lab`.
- Namespace dos módulos.
- Identificador de fio: `easydrop/1` (estável, independente do nome comercial).
- Diretório do projeto.

## 4. Migração, se o nome mudar

1. Nome de exibição e ícone.
2. `applicationId` e namespace.
3. Nome do repositório e dos artefatos.
4. Metadados do F-Droid.
5. README e capturas.

O identificador de fio **não** muda; se mudar, é uma versão nova do protocolo.
