package br.com.hrb.crm.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import br.com.hrb.crm.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "crm.sidecar-segredo=segredo-de-teste-com-mais-de-32-caracteres",
        "crm.retencao-dias=30",
        "crm.midia-dir=target/midia-teste",
        "crm.retencao-cron=-",
        "crm.alerta-cron=-",
        "crm.trello.chave=chave",
        "crm.trello.token=token",
        "crm.trello.segredo-app=" + TrelloWebhookTest.SEGREDO,
        "crm.trello.callback-url=" + TrelloWebhookTest.CALLBACK,
        "crm.trello.usuario-sync-id=" + TrelloWebhookTest.SYNC})
@AutoConfigureMockMvc
class TrelloWebhookTest {

    static final String SEGREDO = "segredo-do-app-trello";
    static final String CALLBACK = "https://crm.hrb.com.br/trello/webhook";
    static final String SYNC = "membro-sync";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;

    @BeforeEach
    void quadros() {
        db.sql("truncate visao_trello, midia, mensagem, contato, numero_whatsapp, usuario restart identity cascade").update();
        db.sql("insert into usuario (nome, papel) values ('Vendedor 1', 'VENDEDOR')").update();
        db.sql("insert into numero_whatsapp (vendedor_id, apelido, telefone) values (1, 'Vendas 1', '5511900000001')").update();
        db.sql("insert into visao_trello (nome, id_quadro, numero_whatsapp_id) values ('Vendedor 1', 'q-vendedor', 1)").update();
        db.sql("insert into visao_trello (nome, id_quadro) values ('Geral', 'q-geral')").update();
    }

    /** Vetor do exemplo oficial do Trello em Node: createHmac('sha1', segredo).update(corpo + url).digest('base64'). */
    @Test
    void assinaturaConfereComAImplementacaoDeReferenciaDoTrello() {
        byte[] corpo = "{\"action\":{\"type\":\"updateCard\",\"data\":{\"card\":{\"name\":\"Pastilha de freio dianteira - João\"}}}}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(TrelloWebhook.assinaturaValida(SEGREDO, corpo, CALLBACK, "gyGl1tu9pxWFZFoVwxUKoPS6wUU=")).isTrue();
        assertThat(TrelloWebhook.assinaturaValida(SEGREDO, corpo, CALLBACK + "/", "gyGl1tu9pxWFZFoVwxUKoPS6wUU=")).isFalse();
        assertThat(TrelloWebhook.assinaturaValida(SEGREDO, corpo, CALLBACK, "não é base64")).isFalse();
    }

    @Test
    void headDeCriacaoResponde200() throws Exception {
        mvc.perform(head("/trello/webhook")).andExpect(status().isOk());
    }

    @Test
    void semAssinaturaOuComAssinaturaErradaRecusa() throws Exception {
        String corpo = evento("q-vendedor", "vendedor-1");
        mvc.perform(post("/trello/webhook").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/trello/webhook").contentType(MediaType.APPLICATION_JSON).content(corpo)
                        .header("X-Trello-Webhook", assinar("outro segredo", corpo)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void edicaoDoVendedorNoProprioQuadroEhAceita() throws Exception {
        enviar(evento("q-vendedor", "vendedor-1")).andExpect(content().string("ACEITO"));
    }

    @Test
    void alteracaoFeitaPeloProprioSyncEhEco() throws Exception {
        enviar(evento("q-vendedor", SYNC)).andExpect(content().string("ECO"));
    }

    @Test
    void quadroGeralEhIgnorado() throws Exception {
        enviar(evento("q-geral", "gestor")).andExpect(content().string("QUADRO_GERAL"));
    }

    @Test
    void quadroForaDaVisaoTrelloEhIgnorado() throws Exception {
        enviar(evento("q-outro", "vendedor-1")).andExpect(content().string("QUADRO_DESCONHECIDO"));
        enviar("{\"action\": {}}").andExpect(content().string("QUADRO_DESCONHECIDO"));
    }

    private ResultActions enviar(String corpo) throws Exception {
        return mvc.perform(post("/trello/webhook").contentType(MediaType.APPLICATION_JSON).content(corpo)
                        .header("X-Trello-Webhook", assinar(SEGREDO, corpo)))
                .andExpect(status().isOk());
    }

    private static String evento(String quadro, String membro) {
        return """
                {"model": {"id": "%s"},
                 "action": {"type": "updateCard", "idMemberCreator": "%s", "data": {"board": {"id": "%s"}}}}"""
                .formatted(quadro, membro, quadro);
    }

    private static String assinar(String segredo, String corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal((corpo + CALLBACK).getBytes(StandardCharsets.UTF_8)));
    }
}
