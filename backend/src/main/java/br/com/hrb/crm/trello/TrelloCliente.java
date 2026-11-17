package br.com.hrb.crm.trello;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Chamadas à API do Trello com o token do usuário dedicado do sync.
 * Limites: 300 req/10 s por chave e 100 por token. Quando um x-rate-limit-*-remaining zera, a próxima chamada
 * espera a janela dele; no 429, espera em dobro (1, 2, 4, 8, 16 s) e desiste na 6ª tentativa,
 * para a fila de saída tentar de novo depois.
 */
@Component
@ConditionalOnProperty(prefix = "crm.trello", name = "segredo-app")
public class TrelloCliente {

    static final int TENTATIVAS = 6;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final TrelloProps props;

    Duration esperaInicial = Duration.ofSeconds(1); // teste encurta
    private Instant liberadoEm = Instant.EPOCH;

    TrelloCliente(ObjectMapper json, TrelloProps props) {
        this.json = json;
        this.props = props;
    }

    /**
     * @param caminho ex.: "/cards/abc"
     * @param corpo   serializado como JSON; null = sem corpo
     * @return a resposta, ou null se veio vazia
     * @throws TrelloErro status diferente de 2xx, ou 429 depois de todas as tentativas
     */
    // ponytail: synchronized = uma chamada por vez no processo; sobra para 3 quadros e um worker de sync.
    public synchronized JsonNode chamar(String metodo, String caminho, Object corpo) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.apiUrl() + caminho))
                // no cabeçalho, e não na URL, para o token não ir parar em log de proxy
                .header("Authorization", "OAuth oauth_consumer_key=\"" + props.chave() + "\", oauth_token=\"" + props.token() + "\"")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .timeout(Duration.ofSeconds(30))
                .build();

        Duration espera = esperaInicial;
        for (int tentativa = 1; ; tentativa++) {
            Duration falta = Duration.between(Instant.now(), liberadoEm);
            if (falta.isPositive()) Thread.sleep(falta);

            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            frear(r.headers());
            if (r.statusCode() == 429 && tentativa < TENTATIVAS) {
                Thread.sleep(espera);
                espera = espera.multipliedBy(2);
                continue;
            }
            if (r.statusCode() / 100 != 2) throw new TrelloErro(r.statusCode(), r.body());
            return r.body().isBlank() ? null : json.readTree(r.body());
        }
    }

    /** Se o limite da chave ou do token zerou, segura a próxima chamada até a janela dele passar. */
    private void frear(HttpHeaders h) {
        for (String limite : List.of("token", "key")) {
            long restante = h.firstValueAsLong("x-rate-limit-api-" + limite + "-remaining").orElse(1);
            long janelaMs = h.firstValueAsLong("x-rate-limit-api-" + limite + "-interval-ms").orElse(10_000);
            Instant fim = Instant.now().plusMillis(janelaMs);
            if (restante <= 0 && fim.isAfter(liberadoEm)) liberadoEm = fim;
        }
    }

    public static class TrelloErro extends RuntimeException {
        public final int status;

        TrelloErro(int status, String corpo) {
            super("Trello respondeu " + status + ": " + corpo);
            this.status = status;
        }
    }
}
