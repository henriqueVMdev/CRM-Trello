package br.com.hrb.crm.ingestao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Formato interno de uma mensagem, convertido da mensagem crua do Baileys (WAMessage).
 * O resto do sistema só enxerga este formato.
 */
record MensagemWhatsapp(
        String idExterno,
        String lid,        // "123@lid", quando conhecido
        String telefone,   // só dígitos, quando conhecido
        boolean deMim,     // true = saída (o vendedor escreveu)
        String tipo,       // chave do conteúdo no proto: conversation, imageMessage, ...
        String texto,
        Instant data,
        String nome) {     // pushName do cliente; null na saída

    /** Invólucros que o Baileys também abre (normalizeMessageContent). */
    private static final List<String> INVOLUCROS = List.of(
            "ephemeralMessage", "viewOnceMessage", "documentWithCaptionMessage",
            "viewOnceMessageV2", "viewOnceMessageV2Extension", "editedMessage",
            "associatedChildMessage");

    private static final Set<String> NAO_E_CONTEUDO = Set.of("messageContextInfo", "senderKeyDistributionMessage");

    /**
     * @return vazio quando a conversa não é individual (grupo, lista de transmissão, status, canal)
     * @throws IllegalArgumentException se faltar key.id ou key.remoteJid
     */
    static Optional<MensagemWhatsapp> de(JsonNode m) {
        JsonNode key = m.path("key");
        String id = texto(key.path("id"));
        String jid = texto(key.path("remoteJid"));
        if (id == null || jid == null) {
            throw new IllegalArgumentException("mensagem sem key.id ou key.remoteJid");
        }
        if (!individual(jid)) {
            return Optional.empty();
        }

        String lid = null, telefone = null;
        for (String j : new String[] {jid, texto(key.path("remoteJidAlt"))}) {
            if (j == null) continue;
            if (j.endsWith("@lid")) lid = usuario(j) + "@lid";
            else if (j.endsWith("@s.whatsapp.net")) telefone = usuario(j);
        }

        JsonNode c = desembrulhar(m.path("message"));
        boolean deMim = key.path("fromMe").asBoolean(false);
        return Optional.of(new MensagemWhatsapp(
                id, lid, telefone, deMim, tipo(c), textoDe(c), data(m.path("messageTimestamp")),
                deMim ? null : texto(m.path("pushName"))));
    }

    /** "5511999999999:12@s.whatsapp.net" -> "5511999999999" (tira servidor e dispositivo). */
    static String usuario(String jid) {
        return jid.substring(0, jid.indexOf('@')).split(":")[0];
    }

    private static boolean individual(String jid) {
        return jid.endsWith("@s.whatsapp.net") || jid.endsWith("@lid");
    }

    private static JsonNode desembrulhar(JsonNode c) {
        for (int i = 0; i < 5; i++) {
            JsonNode atual = c;
            Optional<JsonNode> dentro = INVOLUCROS.stream()
                    .map(w -> atual.path(w).path("message"))
                    .filter(JsonNode::isObject)
                    .findFirst();
            if (dentro.isEmpty()) return c;
            c = dentro.get();
        }
        return c;
    }

    private static String tipo(JsonNode c) {
        if (!c.isObject()) return "sem_conteudo"; // ex.: stub de chamada, falha de decifrar
        return c.properties().stream()
                .filter(e -> !e.getValue().isNull() && !NAO_E_CONTEUDO.contains(e.getKey()))
                .map(java.util.Map.Entry::getKey)
                .findFirst().orElse("sem_conteudo");
    }

    private static String textoDe(JsonNode c) {
        for (JsonNode n : List.of(
                c.path("conversation"),
                c.path("extendedTextMessage").path("text"),
                c.path("imageMessage").path("caption"),
                c.path("videoMessage").path("caption"),
                c.path("documentMessage").path("caption"))) {
            if (texto(n) != null) return texto(n);
        }
        return null;
    }

    /** messageTimestamp vem como número, string ou Long do protobufjs ({low, high}). */
    private static Instant data(JsonNode t) {
        long segundos;
        if (t.isNumber()) segundos = t.longValue();
        else if (t.isString()) segundos = Long.parseLong(t.stringValue());
        else if (t.isObject()) segundos = (t.path("high").asLong(0) << 32) | (t.path("low").asLong(0) & 0xFFFFFFFFL);
        else throw new IllegalArgumentException("mensagem sem messageTimestamp");
        return Instant.ofEpochSecond(segundos);
    }

    private static String texto(JsonNode n) {
        return n.isString() && !n.stringValue().isBlank() ? n.stringValue() : null;
    }
}
