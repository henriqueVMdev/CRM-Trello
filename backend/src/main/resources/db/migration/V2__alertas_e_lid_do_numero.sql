-- @lid do próprio número: para descartar conversa entre números de trabalho que chega só pelo @lid.
alter table numero_whatsapp add column lid text unique check (lid like '%@lid');

-- Pulso do sidecar: chega pela mesma fila dos eventos, então pulso atrasado = sidecar parado,
-- backend inacessível ou fila acumulando.
alter table numero_whatsapp add column pulso_em timestamptz;
alter table numero_whatsapp add column rejeitados integer not null default 0;
