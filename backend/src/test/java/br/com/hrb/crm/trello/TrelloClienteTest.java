package br.com.hrb.crm.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

class TrelloClienteTest {

    /** Resposta roteirizada do Trello falso. */
    record Resposta(int status, String corpo, Map<String, String> cabecalhos) {
        static Resposta ok(String corpo) { return new Resposta(200, corpo, Map.of()); }
        static Resposta limite() { return new Resposta(429, "{\"error\": \"API_TOKEN_LIMIT_EXCEEDED\"}", Map.of()); }
    }

    record Pedido(long emMs, String metodo, String caminho, String autorizacao, String corpo) {}

    final ConcurrentLinkedQueue<Resposta> roteiro = new ConcurrentLinkedQueue<>();
    final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    HttpServer trello;
    TrelloCliente cliente;

    @BeforeEach
    void subir() throws Exception {
        trello = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trello.createContext("/", t -> {
            pedidos.add(new Pedido(System.currentTimeMillis(), t.getRequestMethod(), t.getRequestURI().getPath(),
                    t.getRequestHeaders().getFirst("Authorization"),
                    new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Resposta r = roteiro.remove();
            r.cabecalhos().forEach(t.getResponseHeaders()::add);
            byte[] corpo = r.corpo().getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(r.status(), corpo.length == 0 ? -1 : corpo.length);
            if (corpo.length > 0) t.getResponseBody().write(corpo);
            t.close();
        });
        trello.start();
        URI api = URI.create("http://127.0.0.1:" + trello.getAddress().getPort() + "/1");
        cliente = new TrelloCliente(JsonMapper.builder().build(),
                new TrelloProps("chave1", "token1", "segredo", "https://x/trello/webhook", "sync", api,
                        org.springframework.util.unit.DataSize.ofKilobytes(256)));
        cliente.esperaInicial = Duration.ofMillis(10);
    }

    @AfterEach
    void descer() {
        trello.stop(0);
    }

    @Test
    void mandaTokenNoCabecalhoECorpoEmJson() throws Exception {
        roteiro.add(Resposta.ok("{\"id\": \"c1\"}"));

        var r = cliente.chamar("PUT", "/cards/c1", Map.of("name", "Pastilha"));

        assertThat(r.path("id").stringValue()).isEqualTo("c1");
        Pedido p = pedidos.getFirst();
        assertThat(p.metodo()).isEqualTo("PUT");
        assertThat(p.caminho()).isEqualTo("/1/cards/c1");
        assertThat(p.autorizacao()).isEqualTo("OAuth oauth_consumer_key=\"chave1\", oauth_token=\"token1\"");
        assertThat(p.corpo()).isEqualTo("{\"name\":\"Pastilha\"}");
    }

    @Test
    void limite429EsperaETentaDeNovo() throws Exception {
        roteiro.add(Resposta.limite());
        roteiro.add(Resposta.limite());
        roteiro.add(Resposta.ok("{}"));

        cliente.chamar("GET", "/boards/b1", null);

        assertThat(pedidos).hasSize(3);
        // espera crescente: 10 ms e depois 20 ms
        assertThat(pedidos.get(1).emMs() - pedidos.get(0).emMs()).isGreaterThanOrEqualTo(10);
        assertThat(pedidos.get(2).emMs() - pedidos.get(1).emMs()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void desisteDepoisDeTodasAsTentativas() {
        for (int i = 0; i < TrelloCliente.TENTATIVAS; i++) roteiro.add(Resposta.limite());

        assertThatThrownBy(() -> cliente.chamar("GET", "/boards/b1", null))
                .isInstanceOfSatisfying(TrelloCliente.TrelloErro.class, e -> assertThat(e.status).isEqualTo(429));
        assertThat(pedidos).hasSize(TrelloCliente.TENTATIVAS);
    }

    @Test
    void esperaDe429NaoSeguraAsOutrasChamadas() throws Exception {
        cliente.esperaInicial = Duration.ofMillis(1500);
        roteiro.add(Resposta.limite());     // 1ª chamada leva 429 e espera 1,5 s
        roteiro.add(Resposta.ok("{}"));     // 2ª chamada, feita durante a espera
        roteiro.add(Resposta.ok("{}"));     // nova tentativa da 1ª

        var primeira = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                cliente.chamar("GET", "/boards/b1", null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        while (pedidos.isEmpty()) Thread.onSpinWait();

        long inicio = System.currentTimeMillis();
        cliente.chamar("GET", "/cards/c1", null);
        long duracao = System.currentTimeMillis() - inicio;
        primeira.get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(duracao).isLessThan(1000);
        assertThat(pedidos).extracting(Pedido::caminho).containsExactly("/1/boards/b1", "/1/cards/c1", "/1/boards/b1");
    }

    @Test
    void outroErroNaoTentaDeNovo() {
        roteiro.add(new Resposta(400, "invalid id", Map.of()));

        assertThatThrownBy(() -> cliente.chamar("GET", "/cards/x", null)).isInstanceOf(TrelloCliente.TrelloErro.class);
        assertThat(pedidos).hasSize(1);
    }

    /** Cada limite sozinho zerado, com os outros sobrando: tirar qualquer um da lista quebra o teste. */
    @ParameterizedTest
    @ValueSource(strings = {"x-rate-limit-api-token", "x-rate-limit-api-key", "x-rate-limit-member"})
    void limiteZeradoSeguraAProximaChamadaAteAJanelaPassar(String zerado) throws Exception {
        Map<String, String> cabecalhos = new java.util.HashMap<>();
        for (String limite : List.of("x-rate-limit-api-token", "x-rate-limit-api-key", "x-rate-limit-member")) {
            cabecalhos.put(limite + "-remaining", limite.equals(zerado) ? "0" : "50");
            cabecalhos.put(limite + "-interval-ms", limite.equals(zerado) ? "300" : "10000");
        }
        roteiro.add(new Resposta(200, "{}", cabecalhos));
        roteiro.add(Resposta.ok("{}"));

        cliente.chamar("GET", "/boards/b1", null);
        cliente.chamar("GET", "/boards/b1", null);

        assertThat(pedidos.get(1).emMs() - pedidos.get(0).emMs()).isGreaterThanOrEqualTo(250);
    }

    @Test
    void comLimiteSobrandoNaoEspera() throws Exception {
        roteiro.add(new Resposta(200, "{}", Map.of(
                "x-rate-limit-api-token-remaining", "99", "x-rate-limit-api-token-interval-ms", "10000")));
        roteiro.add(Resposta.ok(""));

        cliente.chamar("GET", "/boards/b1", null);
        assertThat(cliente.chamar("DELETE", "/webhooks/w1", null)).isNull();

        assertThat(pedidos.get(1).emMs() - pedidos.get(0).emMs()).isLessThan(1000);
    }
}
