package br.com.hrb.crm.ingestao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.hrb.crm.CrmProps;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Entrada dos eventos do sidecar. Contrato v1 em docs/contrato-sidecar.md.
 * Só acessível pela rede interna do Compose.
 */
@RestController
class EventoController {

    private final Adaptador adaptador;
    private final ObjectMapper json;
    private final byte[] segredo;

    EventoController(Adaptador adaptador, ObjectMapper json, CrmProps props) {
        this.adaptador = adaptador;
        this.json = json;
        this.segredo = props.sidecarSegredo().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(path = "/interno/eventos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<String> receber(
            @RequestHeader(name = "X-Assinatura", required = false) String assinatura,
            @RequestPart("evento") MultipartFile evento,
            @RequestPart(name = "arquivo", required = false) MultipartFile arquivo) throws IOException {

        byte[] corpo = evento.getBytes();
        if (!assinaturaValida(segredo, corpo, assinatura)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("assinatura inválida");
        }
        JsonNode e = json.readTree(corpo);
        if (e.path("v").asInt(0) != 1) {
            throw new IllegalArgumentException("versão de contrato não suportada: " + e.path("v"));
        }
        String numero = texto(e.path("numero"));

        switch (String.valueOf(texto(e.path("tipo")))) {
            case "mensagem" -> {
                JsonNode m = e.path("midia");
                Adaptador.Midia midia = null;
                if (m.isObject()) {
                    if (arquivo == null) throw new IllegalArgumentException("evento declara mídia mas não trouxe o arquivo");
                    String mime = texto(m.path("mime"));
                    midia = new Adaptador.Midia(mime == null ? "application/octet-stream" : mime,
                            exigir(texto(m.path("sha256")), "midia.sha256"), arquivo.getInputStream());
                }
                return ResponseEntity.ok(adaptador.gravarMensagem(
                        exigir(numero, "numero"), e.path("mensagem"), midia, texto(e.path("midiaErro"))).name());
            }
            case "conexao" -> adaptador.estadoSessao(exigir(numero, "numero"), exigir(texto(e.path("estado")), "estado"),
                    texto(e.path("lid")));
            case "pulso" -> {
                if (!e.path("rejeitados").canConvertToInt()) throw new IllegalArgumentException("pulso sem rejeitados");
                adaptador.pulso(exigir(numero, "numero"), e.path("rejeitados").intValue());
            }
            case "lid" -> adaptador.mapearLid(exigir(texto(e.path("lid")), "lid"), exigir(texto(e.path("pn")), "pn"));
            default -> throw new IllegalArgumentException("tipo de evento desconhecido: " + e.path("tipo"));
        }
        return ResponseEntity.ok("OK");
    }

    /** HMAC-SHA256 do corpo do evento, em hexadecimal, comparado em tempo constante. */
    static boolean assinaturaValida(byte[] segredo, byte[] corpo, String assinatura) {
        if (assinatura == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo, "HmacSHA256"));
            byte[] esperado = mac.doFinal(corpo);
            byte[] recebido = HexFormat.of().parseHex(assinatura.trim());
            return MessageDigest.isEqual(esperado, recebido);
        } catch (IllegalArgumentException e) {
            return false; // não é hexadecimal
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Só aceita string de verdade: null, número ou ausente viram null. */
    private static String texto(JsonNode n) {
        return n.isString() ? n.stringValue() : null;
    }

    private static String exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException("evento sem " + campo);
        return valor;
    }

    /** 400 = o sidecar não deve reenviar igual; guarda o evento à parte para análise. */
    @ExceptionHandler({IllegalArgumentException.class, JacksonException.class, Adaptador.MidiaCorrompida.class})
    ResponseEntity<String> invalido(Exception ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
