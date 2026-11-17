-- Os 3 quadros do Trello. O webhook usa o ID do quadro para saber a origem (regras 10 e 11).
-- O mapa de status para lista e o dos campos customizados entram na fase 2, com a lista de status confirmada.
create table visao_trello (
    id                 bigint generated always as identity primary key,
    nome               text not null,
    id_quadro          text not null unique,
    -- null = quadro geral: só leitura, o webhook dele é ignorado
    numero_whatsapp_id bigint unique references numero_whatsapp (id)
);
