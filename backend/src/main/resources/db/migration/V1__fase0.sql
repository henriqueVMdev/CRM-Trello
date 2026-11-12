-- Fase 0: ponte e proteção. Só o que o adaptador grava.
-- As demais tabelas de docs/arquitetura.md entram nas fases em que são usadas.

create table usuario (
    id    bigint generated always as identity primary key,
    nome  text not null,
    papel text not null check (papel in ('GESTOR', 'VENDEDOR'))
);

create table numero_whatsapp (
    id            bigint generated always as identity primary key,
    vendedor_id   bigint not null references usuario (id),
    apelido       text   not null,
    -- só dígitos, com DDI (ex.: 5511999999999). É como o sidecar identifica o número.
    telefone      text   not null unique check (telefone ~ '^[0-9]+$'),
    ativo         boolean not null default true,
    estado_sessao text   not null default 'DESCONHECIDO',
    estado_em     timestamptz
);

create table contato (
    id       bigint generated always as identity primary key,
    lid      text unique check (lid like '%@lid'),
    telefone text unique check (telefone ~ '^[0-9]+$'),
    nome     text,
    check (lid is not null or telefone is not null)
);

create table mensagem (
    id                 bigint generated always as identity primary key,
    numero_whatsapp_id bigint      not null references numero_whatsapp (id),
    contato_id         bigint      not null references contato (id),
    id_externo         text        not null,
    direcao            text        not null check (direcao in ('ENTRADA', 'SAIDA')),
    tipo               text        not null,
    texto              text,
    data               timestamptz not null,
    recebida_em        timestamptz not null default now(),
    midia_erro         text,
    -- mensagem crua do Baileys, para reprocessar se o adaptador tiver bug
    bruto              jsonb       not null,
    unique (numero_whatsapp_id, id_externo)
);

create index mensagem_contato_data on mensagem (contato_id, data);
create index mensagem_data on mensagem (data);

create table midia (
    id          bigint generated always as identity primary key,
    mensagem_id bigint not null unique references mensagem (id) on delete cascade,
    -- null até a fase 1 classificar
    tipo        text check (tipo in ('PLACA', 'PECA', 'OUTRO')),
    mime        text   not null,
    caminho     text   not null,
    sha256      text   not null,
    tamanho     bigint not null
);

create index midia_sha256 on midia (sha256);
