package br.com.hrb.crm.trello;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook de volta dos 3 quadros: todos apontam para este endereço, e o ID do quadro diz a origem.
 * É o único caminho do backend exposto na internet (pelo proxy reverso).
 */
@RestController
@ConditionalOnProperty(prefix = "crm.trello", name = "segredo-app")
class TrelloWebhook {

    enum Resultado { ACEITO, ECO, QUADRO_GERAL, QUADRO_DESCONHECIDO, JSON_INVALIDO }

    private static final Logger log = LoggerFactory.getLogger(TrelloWebhook.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final TrelloProps props;

    TrelloWebhook(JdbcClient db, ObjectMapper json, TrelloProps props) {
        this.db = db;
        this.json = json;
        this.props = props;
    }

    /** O Trello faz um HEAD ao criar o webhook e só cria se a resposta for 200. */
    @RequestMapping(path = "/trello/webhook", method = RequestMethod.HEAD)
    ResponseEntity<Void> criar() {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/trello/webhook")
    ResponseEntity<String> receber(@RequestHeader(name = "X-Trello-Webhook", required = false) String assinatura,
                                   HttpServletRequest req) throws IOException {
        // Rota aberta na internet: o corpo é lido com teto antes da assinatura, senão qualquer um enche o heap.
        int max = Math.toIntExact(props.corpoMax().toBytes());
        byte[] corpo = req.getContentLengthLong() > max ? null : lerAte(req.getInputStream(), max);
        if (corpo == null) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body("corpo acima de " + props.corpoMax());
        }
        if (!assinaturaValida(props.segredoApp(), corpo, props.callbackUrl(), assinatura)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("assinatura inválida");
        }
        // Ignorados também respondem 200: erro faria o Trello reenviar à toa e, repetido, desativar o webhook.
        JsonNode evento;
        try {
            evento = json.readTree(corpo);
        } catch (JacksonException e) {
            log.warn("webhook do Trello assinado mas com JSON inválido: {}", e.getOriginalMessage());
            return ResponseEntity.ok(Resultado.JSON_INVALIDO.name());
        }
        return ResponseEntity.ok(classificar(evento).name());
    }

    /** Lê no máximo max bytes; null se o corpo passar disso. Não confia no Content-Length (pode vir chunked). */
    static byte[] lerAte(InputStream in, int max) throws IOException {
        byte[] lido = in.readNBytes(max + 1);
        return lido.length > max ? null : lido;
    }

    Resultado classificar(JsonNode evento) {
        String quadro = texto(evento.path("model").path("id"));
        Optional<Boolean> geral = quadro == null ? Optional.empty()
                : db.sql("select numero_whatsapp_id is null from visao_trello where id_quadro = ?")
                        .param(quadro).query(Boolean.class).optional();
        if (geral.isEmpty()) {
            log.warn("webhook de quadro que não está em visao_trello: {}", quadro);
            return Resultado.QUADRO_DESCONHECIDO;
        }
        if (geral.get()) return Resultado.QUADRO_GERAL; // edição só vale do quadro do vendedor
        if (props.usuarioSyncId().equals(texto(evento.path("action").path("idMemberCreator")))) {
            return Resultado.ECO;
        }
        // ponytail: aplicar status e campos customizados entra com o modelo de orçamento (fase 2).
        // O Trello reenvia entregas: action.id vira chave única na tabela de eventos de correção, gravado na mesma
        // transação que aplica a correção, para a mesma edição não contar duas vezes (regra 6).
        return Resultado.ACEITO;
    }

    /** Base64 do HMAC-SHA1 de (corpo + callbackURL) com o segredo do app, comparado em tempo constante. */
    static boolean assinaturaValida(String segredo, byte[] corpo, String callbackUrl, String assinatura) {
        if (assinatura == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(segredo.getBytes(UTF_8), "HmacSHA1"));
            mac.update(corpo);
            byte[] esperado = mac.doFinal(callbackUrl.getBytes(UTF_8));
            return MessageDigest.isEqual(esperado, Base64.getDecoder().decode(assinatura.trim()));
        } catch (IllegalArgumentException e) {
            return false; // não é base64
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String texto(JsonNode n) {
        return n.isString() ? n.stringValue() : null;
    }
}
