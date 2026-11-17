package br.com.hrb.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "crm.sidecar-segredo=" + FaseZeroTest.SEGREDO,
        "crm.retencao-dias=30",
        "crm.retencao-cron=-",
        "crm.alerta-cron=-"})
@AutoConfigureMockMvc
class FaseZeroTest {

    static final String SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres";
    static final String NUMERO = "5511900000001";

    @TempDir
    static Path midiaDir;

    /** Faz o papel do healthchecks.io: guarda "MÉTODO caminho corpo" de cada ping. */
    static final List<String> PINGS = new CopyOnWriteArrayList<>();
    static final HttpServer ALERTA;
    static {
        try {
            ALERTA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ALERTA.createContext("/", t -> {
            PINGS.add(t.getRequestMethod() + " " + t.getRequestURI().getPath() + " "
                    + new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            t.sendResponseHeaders(200, -1);
            t.close();
        });
        ALERTA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("crm.midia-dir", midiaDir::toString);
        r.add("crm.alerta-url", () -> "http://127.0.0.1:" + ALERTA.getAddress().getPort() + "/ping/abc");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient db;
    @Autowired Retencao retencao;
    @Autowired Monitor monitor;

    @BeforeEach
    void limpar() {
        db.sql("truncate midia, mensagem, contato, numero_whatsapp, usuario restart identity cascade").update();
        long vendedor = db.sql("insert into usuario (nome, papel) values ('Vendedor 1', 'VENDEDOR') returning id")
                .query(Long.class).single();
        db.sql("insert into numero_whatsapp (vendedor_id, apelido, telefone) values (?, 'Vendas 1', ?)")
                .params(vendedor, NUMERO).update();
        PINGS.clear();
    }

    @Test
    void assinaturaErradaNaoGravaNada() throws Exception {
        byte[] evento = mensagem("A1", "1@lid", null, false, "{\"conversation\": \"oi\"}", null).getBytes();
        mvc.perform(multipart("/interno/eventos")
                        .file(new MockMultipartFile("evento", "", "application/json", evento))
                        .header("X-Assinatura", assinar("outro segredo qualquer com 32+ caracteres", evento)))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/interno/eventos")
                        .file(new MockMultipartFile("evento", "", "application/json", evento)))
                .andExpect(status().isUnauthorized());

        assertThat(contar("mensagem")).isZero();
    }

    @Test
    void mensagemRepetidaGravaUmaVez() throws Exception {
        String e = mensagem("A1", "1@lid", "5511988887777@s.whatsapp.net", false, "{\"conversation\": \"oi\"}", null);
        enviar(e, null).andExpect(content().string("GRAVADA"));
        enviar(e, null).andExpect(content().string("DUPLICADA"));

        assertThat(contar("mensagem")).isEqualTo(1);
        Map<String, Object> m = db.sql("select direcao, tipo, texto, bruto->'key'->>'id' as id from mensagem").query().singleRow();
        assertThat(m).containsEntry("direcao", "ENTRADA").containsEntry("tipo", "conversation")
                .containsEntry("texto", "oi").containsEntry("id", "A1");
    }

    @Test
    void saidaDoVendedorEhGravada() throws Exception {
        enviar(mensagem("S1", "5511988887777@s.whatsapp.net", null, true, "{\"conversation\": \"350 o par\"}", null), null)
                .andExpect(content().string("GRAVADA"));

        assertThat(db.sql("select direcao from mensagem").query(String.class).single()).isEqualTo("SAIDA");
    }

    @Test
    void grupoNumeroDesconhecidoEConversaConsigoMesmoSaoDescartados() throws Exception {
        enviar(mensagem("G1", "120363000000000000@g.us", null, false, "{\"conversation\": \"oi\"}", null), null)
                .andExpect(content().string("DESCARTADA"));
        enviar(mensagem("P1", NUMERO + "@s.whatsapp.net", null, true, "{\"conversation\": \"nota\"}", null), null)
                .andExpect(content().string("DESCARTADA"));
        String deOutroNumero = mensagem("D1", "1@lid", null, false, "{\"conversation\": \"oi\"}", null)
                .replace(NUMERO, "5511900000099");
        enviar(deOutroNumero, null).andExpect(content().string("DESCARTADA"));

        assertThat(contar("mensagem")).isZero();
        assertThat(contar("contato")).isZero();
    }

    @Test
    void midiaEhSalvaPeloHash() throws Exception {
        byte[] foto = "bytes da foto da placa".getBytes();
        String sha = sha256(foto);
        String e = mensagem("M1", "1@lid", null, false, "{\"imageMessage\": {\"mimetype\": \"image/jpeg\"}}",
                "{\"mime\": \"image/jpeg\", \"sha256\": \"" + sha + "\"}");
        enviar(e, foto).andExpect(content().string("GRAVADA"));

        Map<String, Object> d = db.sql("select caminho, sha256, tamanho, mime from midia").query().singleRow();
        assertThat(d).containsEntry("sha256", sha).containsEntry("tamanho", (long) foto.length).containsEntry("mime", "image/jpeg");
        assertThat(Files.readAllBytes(midiaDir.resolve((String) d.get("caminho")))).isEqualTo(foto);
    }

    @Test
    void midiaQueNaoConfereComOHashDesfazTudo() throws Exception {
        String e = mensagem("M2", "1@lid", null, false, "{\"imageMessage\": {}}",
                "{\"mime\": \"image/jpeg\", \"sha256\": \"" + sha256("outra coisa".getBytes()) + "\"}");
        enviar(e, "bytes".getBytes()).andExpect(status().isBadRequest());

        assertThat(contar("mensagem")).isZero();
        assertThat(contar("contato")).isZero();
    }

    @Test
    void midiaDeclaradaSemArquivoEhRecusada() throws Exception {
        enviar(mensagem("M3", "1@lid", null, false, "{\"imageMessage\": {}}", "{\"mime\": \"image/jpeg\", \"sha256\": \"00\"}"), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void falhaDeDownloadAindaGravaAMensagem() throws Exception {
        String e = mensagem("M4", "1@lid", null, false, "{\"imageMessage\": {}}", null)
                .replace("\"midiaErro\": null", "\"midiaErro\": \"mídia expirada\"");
        enviar(e, null).andExpect(content().string("GRAVADA"));

        assertThat(db.sql("select midia_erro from mensagem").query(String.class).single()).isEqualTo("mídia expirada");
    }

    @Test
    void contatoVistoSoPorLidESoPorTelefoneEhJuntadoPeloMapa() throws Exception {
        enviar(mensagem("A1", "777@lid", null, false, "{\"conversation\": \"oi\"}", null), null);
        enviar(mensagem("A2", "5511988887777@s.whatsapp.net", null, true, "{\"conversation\": \"olá\"}", null), null);
        assertThat(contar("contato")).isEqualTo(2);

        enviar("{\"v\": 1, \"tipo\": \"lid\", \"lid\": \"777@lid\", \"pn\": \"5511988887777@s.whatsapp.net\"}", null)
                .andExpect(status().isOk());

        Map<String, Object> c = db.sql("select id, lid, telefone, nome from contato").query().singleRow();
        assertThat(c).containsEntry("lid", "777@lid").containsEntry("telefone", "5511988887777").containsEntry("nome", "Cliente");
        assertThat(db.sql("select count(distinct contato_id) from mensagem").query(Long.class).single()).isEqualTo(1);
        assertThat(contar("mensagem")).isEqualTo(2);
    }

    @Test
    void lidComTelefoneAlternativoCompletaContatoExistente() throws Exception {
        enviar(mensagem("A1", "777@lid", null, false, "{\"conversation\": \"oi\"}", null), null);
        enviar(mensagem("A2", "777@lid", "5511988887777@s.whatsapp.net", false, "{\"conversation\": \"oi\"}", null), null);

        assertThat(db.sql("select telefone from contato").query(String.class).single()).isEqualTo("5511988887777");
    }

    @Test
    void estadoDaSessao() throws Exception {
        enviar("{\"v\": 1, \"tipo\": \"conexao\", \"numero\": \"" + NUMERO + "\", \"estado\": \"close\"}", null)
                .andExpect(status().isOk());

        assertThat(db.sql("select estado_sessao from numero_whatsapp").query(String.class).single()).isEqualTo("close");
    }

    @Test
    void versaoDeContratoDesconhecidaEhRecusada() throws Exception {
        enviar("{\"v\": 2, \"tipo\": \"conexao\", \"numero\": \"" + NUMERO + "\", \"estado\": \"open\"}", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void retencaoApagaSoOQueVenceuEOArquivoQueNinguemMaisUsa() throws Exception {
        byte[] compartilhada = "mesma foto mandada duas vezes".getBytes();
        byte[] soVelha = "foto só da conversa velha".getBytes();
        long velho = Instant.now().minus(31, ChronoUnit.DAYS).getEpochSecond();
        long novo = Instant.now().minus(29, ChronoUnit.DAYS).getEpochSecond();

        enviar(comData(midiaMsg("V1", "111@lid", compartilhada), velho), compartilhada);
        enviar(comData(midiaMsg("V2", "111@lid", soVelha), velho), soVelha);
        enviar(comData(midiaMsg("N1", "222@lid", compartilhada), novo), compartilhada);
        db.sql("update contato set visto_em = now() - interval '31 days' where lid = '111@lid'").update();

        assertThat(retencao.apagar()).isEqualTo(2);

        assertThat(db.sql("select bruto->'key'->>'id' from mensagem").query(String.class).list()).containsExactly("N1");
        assertThat(db.sql("select lid from contato").query(String.class).list()).containsExactly("222@lid");
        assertThat(contar("midia")).isEqualTo(1);
        assertThat(arquivo(compartilhada)).exists();
        assertThat(arquivo(soVelha)).doesNotExist();
    }

    @Test
    void mapaLidSemMensagemSobreviveARetencaoAteVencer() throws Exception {
        enviar("{\"v\": 1, \"tipo\": \"lid\", \"lid\": \"777@lid\", \"pn\": \"5511988887777@s.whatsapp.net\"}", null)
                .andExpect(status().isOk());

        retencao.apagar();
        enviar(mensagem("A1", "777@lid", null, false, "{\"conversation\": \"oi\"}", null), null);
        assertThat(db.sql("select telefone from mensagem m join contato c on c.id = m.contato_id").query(String.class).single())
                .isEqualTo("5511988887777");

        enviar("{\"v\": 1, \"tipo\": \"lid\", \"lid\": \"888@lid\", \"pn\": \"5511977776666@s.whatsapp.net\"}", null);
        db.sql("update contato set visto_em = now() - interval '31 days' where lid = '888@lid'").update();
        retencao.apagar();
        assertThat(db.sql("select lid from contato").query(String.class).list()).containsExactly("777@lid");
    }

    @Test
    void conversaComOutroNumeroDeTrabalhoEhDescartadaPeloTelefoneOuPeloLid() throws Exception {
        db.sql("insert into numero_whatsapp (vendedor_id, apelido, telefone) values (1, 'Vendas 2', '5511900000002')").update();
        enviar("{\"v\": 1, \"tipo\": \"conexao\", \"numero\": \"5511900000002\", \"estado\": \"open\", \"lid\": \"555:7@lid\"}", null)
                .andExpect(status().isOk());

        enviar(mensagem("V1", "5511900000002@s.whatsapp.net", null, false, "{\"conversation\": \"oi\"}", null), null)
                .andExpect(content().string("DESCARTADA"));
        enviar(mensagem("V2", "555@lid", null, true, "{\"conversation\": \"oi\"}", null), null)
                .andExpect(content().string("DESCARTADA"));
        enviar(mensagem("C1", "556@lid", null, false, "{\"conversation\": \"oi\"}", null), null)
                .andExpect(content().string("GRAVADA"));

        assertThat(db.sql("select bruto->'key'->>'id' from mensagem").query(String.class).list()).containsExactly("C1");
    }

    @Test
    void numeroRecemCadastradoAlertaSessaoESemPulso() {
        assertThat(monitor.problemas()).containsExactly(
                "Vendas 1: sessão do WhatsApp fora (DESCONHECIDO)",
                "Vendas 1: sem pulso do sidecar há mais de 10 min (sidecar parado ou fila acumulando)");
    }

    @Test
    void tudoBemMandaPingDeSucesso() throws Exception {
        conexao("open");
        pulso(0);

        assertThat(monitor.problemas()).isEmpty();
        monitor.verificar();
        assertThat(PINGS).containsExactly("GET /ping/abc ");
    }

    @Test
    void quedaRapidaDaSessaoNaoAlertaMasQuedaLongaSim() throws Exception {
        pulso(0);
        conexao("close");
        assertThat(monitor.problemas()).isEmpty(); // reconexão normal dentro de 5 min

        db.sql("update numero_whatsapp set estado_em = now() - interval '6 minutes'").update();
        assertThat(monitor.problemas()).containsExactly("Vendas 1: sessão do WhatsApp fora (close)");
    }

    @Test
    void pulsoAtrasadoERejeitadosViramAlertaComMotivo() throws Exception {
        conexao("open");
        pulso(2);
        db.sql("update numero_whatsapp set pulso_em = now() - interval '11 minutes'").update();

        monitor.verificar();

        assertThat(PINGS).containsExactly("POST /ping/abc/fail "
                + "Vendas 1: sem pulso do sidecar há mais de 10 min (sidecar parado ou fila acumulando)\n"
                + "Vendas 1: 2 evento(s) recusado(s) em /fila/rejeitados");
    }

    @Test
    void semTrelloConfiguradoNaoExisteRotaDeWebhook() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/trello/webhook").content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void numeroInativoNaoAlerta() {
        db.sql("update numero_whatsapp set ativo = false").update();
        assertThat(monitor.problemas()).isEmpty();
    }

    private void conexao(String estado) throws Exception {
        enviar("{\"v\": 1, \"tipo\": \"conexao\", \"numero\": \"" + NUMERO + "\", \"estado\": \"" + estado + "\"}", null)
                .andExpect(status().isOk());
    }

    private void pulso(int rejeitados) throws Exception {
        enviar("{\"v\": 1, \"tipo\": \"pulso\", \"numero\": \"" + NUMERO + "\", \"rejeitados\": " + rejeitados + "}", null)
                .andExpect(status().isOk());
    }

    // ---- apoio

    private ResultActions enviar(String evento, byte[] arquivo) throws Exception {
        byte[] corpo = evento.getBytes(StandardCharsets.UTF_8);
        var req = multipart("/interno/eventos")
                .file(new MockMultipartFile("evento", "", "application/json", corpo))
                .header("X-Assinatura", assinar(SEGREDO, corpo));
        if (arquivo != null) req.file(new MockMultipartFile("arquivo", "", "application/octet-stream", arquivo));
        return mvc.perform(req);
    }

    private static String mensagem(String id, String jid, String alt, boolean deMim, String conteudo, String midia) {
        return """
                {"v": 1, "tipo": "mensagem", "numero": "%s", "midia": %s, "midiaErro": null,
                 "mensagem": {"key": {"id": "%s", "remoteJid": "%s"%s, "fromMe": %s},
                              "messageTimestamp": 1762871234, "pushName": "Cliente", "message": %s}}"""
                .formatted(NUMERO, midia, id, jid, alt == null ? "" : ", \"remoteJidAlt\": \"" + alt + "\"", deMim, conteudo);
    }

    private static String midiaMsg(String id, String jid, byte[] arquivo) throws Exception {
        return mensagem(id, jid, null, false, "{\"imageMessage\": {}}",
                "{\"mime\": \"image/jpeg\", \"sha256\": \"" + sha256(arquivo) + "\"}");
    }

    private static String comData(String evento, long segundos) {
        return evento.replace("1762871234", Long.toString(segundos));
    }

    private Path arquivo(byte[] conteudo) throws Exception {
        String h = sha256(conteudo);
        return midiaDir.resolve(h.substring(0, 2)).resolve(h.substring(2, 4)).resolve(h);
    }

    private long contar(String tabela) {
        return db.sql("select count(*) from " + tabela).query(Long.class).single();
    }

    private static String assinar(String segredo, byte[] corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corpo));
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
