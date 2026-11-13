-- Última vez que o contato apareceu (mensagem ou mapa @lid). A retenção só apaga contato sem mensagem
-- que não aparece há mais que o prazo: sem isso, o mapa @lid -> telefone que chega antes da primeira
-- mensagem era apagado na rotina das 3h, e a mensagem seguinte ficava sem telefone.
alter table contato add column visto_em timestamptz not null default now();
