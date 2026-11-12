package br.com.hrb.crm.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MensagemWhatsappTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static MensagemWhatsapp converter(String json) {
        return MensagemWhatsapp.de(JSON.readTree(json)).orElseThrow();
    }

    @Test
    void entradaComLidETelefoneAlternativo() {
        MensagemWhatsapp m = converter("""
                {"key": {"id": "ABC", "remoteJid": "987654321:3@lid", "remoteJidAlt": "5511988887777:12@s.whatsapp.net", "fromMe": false},
                 "messageTimestamp": 1762871234, "pushName": "João",
                 "message": {"conversation": "tem pastilha pro gol 2015?"}}""");

        assertThat(m.idExterno()).isEqualTo("ABC");
        assertThat(m.lid()).isEqualTo("987654321@lid");
        assertThat(m.telefone()).isEqualTo("5511988887777");
        assertThat(m.deMim()).isFalse();
        assertThat(m.tipo()).isEqualTo("conversation");
        assertThat(m.texto()).isEqualTo("tem pastilha pro gol 2015?");
        assertThat(m.data()).isEqualTo(Instant.ofEpochSecond(1762871234));
        assertThat(m.nome()).isEqualTo("João");
    }

    @Test
    void saidaDoVendedorNaoLevaNomeDoContato() {
        MensagemWhatsapp m = converter("""
                {"key": {"id": "X1", "remoteJid": "5511988887777@s.whatsapp.net", "fromMe": true},
                 "messageTimestamp": "1762871234", "pushName": "Vendedor HRB",
                 "message": {"extendedTextMessage": {"text": "fica 350 o par"}}}""");

        assertThat(m.deMim()).isTrue();
        assertThat(m.nome()).isNull();
        assertThat(m.lid()).isNull();
        assertThat(m.telefone()).isEqualTo("5511988887777");
        assertThat(m.texto()).isEqualTo("fica 350 o par");
    }

    @ParameterizedTest
    @ValueSource(strings = {"120363000000000000@g.us", "status@broadcast", "1234@broadcast", "1203630000@newsletter"})
    void conversaQueNaoEIndividualEDescartada(String jid) {
        JsonNode m = JSON.readTree("""
                {"key": {"id": "G1", "remoteJid": "%s", "fromMe": false},
                 "messageTimestamp": 1, "message": {"conversation": "oi"}}""".formatted(jid));

        assertThat(MensagemWhatsapp.de(m)).isEmpty();
    }

    @Test
    void abreInvolucrosEIgnoraMetadadosAoAcharOTipo() {
        MensagemWhatsapp m = converter("""
                {"key": {"id": "I1", "remoteJid": "1@lid", "fromMe": false}, "messageTimestamp": 1,
                 "message": {"messageContextInfo": {}, "ephemeralMessage": {"message": {"viewOnceMessageV2": {"message":
                   {"messageContextInfo": {}, "imageMessage": {"caption": "placa do carro", "mimetype": "image/jpeg"}}}}}}}""");

        assertThat(m.tipo()).isEqualTo("imageMessage");
        assertThat(m.texto()).isEqualTo("placa do carro");
    }

    @Test
    void timestampComoLongDoProtobufjs() {
        // low é int32 com sinal: -1 com high 0 vale 2^32 - 1
        MensagemWhatsapp m = converter("""
                {"key": {"id": "L1", "remoteJid": "1@lid"}, "messageTimestamp": {"low": -1, "high": 0, "unsigned": true},
                 "message": {"conversation": "x"}}""");

        assertThat(m.data()).isEqualTo(Instant.ofEpochSecond(4294967295L));
    }

    @Test
    void semConteudoAindaViraMensagem() {
        MensagemWhatsapp m = converter("""
                {"key": {"id": "S1", "remoteJid": "1@lid"}, "messageTimestamp": 1, "messageStubType": 40}""");

        assertThat(m.tipo()).isEqualTo("sem_conteudo");
        assertThat(m.texto()).isNull();
    }

    @Test
    void semIdExternoEhErro() {
        assertThatThrownBy(() -> MensagemWhatsapp.de(JSON.readTree("""
                {"key": {"remoteJid": "1@lid"}, "messageTimestamp": 1}""")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assinaturaHmac() {
        byte[] segredo = "segredo".getBytes();
        byte[] corpo = "{}".getBytes();
        // printf '{}' | openssl dgst -sha256 -hmac segredo
        String certa = "fb628fffaa33657454b42caedf691bd6a3ecb2b2f1e32a33d1f916632fa09b20";
        assertThat(EventoController.assinaturaValida(segredo, corpo, certa)).isTrue();
        assertThat(EventoController.assinaturaValida(segredo, corpo, certa.toUpperCase())).isTrue();
        assertThat(EventoController.assinaturaValida(segredo, "{ }".getBytes(), certa)).isFalse();
        assertThat(EventoController.assinaturaValida("outro".getBytes(), corpo, certa)).isFalse();
        assertThat(EventoController.assinaturaValida(segredo, corpo, certa.substring(2))).isFalse();
        assertThat(EventoController.assinaturaValida(segredo, corpo, null)).isFalse();
        assertThat(EventoController.assinaturaValida(segredo, corpo, "nao-hex")).isFalse();
    }
}
