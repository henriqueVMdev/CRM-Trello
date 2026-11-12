package br.com.hrb.crm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Alertas por "dead man's switch": a cada minuto avisa o serviço externo se está tudo bem ou não.
 * Se o aviso parar de chegar (VPS, Docker ou backend fora), o próprio serviço externo alerta.
 */
@Component
class Monitor {

    private static final Logger log = LoggerFactory.getLogger(Monitor.class);

    private final JdbcClient db;
    private final CrmProps props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    Monitor(JdbcClient db, CrmProps props) {
        this.db = db;
        this.props = props;
        if (props.alertaUrl() == null) log.error("crm.alerta-url não configurada: alertas só no log");
    }

    /** Problemas atuais, um por linha. Vazio = tudo bem. Só leva apelido do número, nunca dado de cliente. */
    List<String> problemas() {
        List<String> p = new ArrayList<>();
        db.sql("""
                select apelido, estado_sessao, rejeitados,
                       estado_sessao <> 'open' and coalesce(estado_em, '-infinity') < now() - make_interval(mins => ?) as sessao_caida,
                       coalesce(pulso_em, '-infinity') < now() - make_interval(mins => ?) as sem_pulso
                from numero_whatsapp where ativo order by id""")
                .params(props.alertaSessaoMinutos(), props.alertaPulsoMinutos())
                .query((rs, i) -> {
                    String n = rs.getString("apelido");
                    if (rs.getBoolean("sessao_caida")) {
                        p.add(n + ": sessão do WhatsApp fora (" + rs.getString("estado_sessao") + ")");
                    }
                    if (rs.getBoolean("sem_pulso")) {
                        p.add(n + ": sem pulso do sidecar há mais de " + props.alertaPulsoMinutos()
                                + " min (sidecar parado ou fila acumulando)");
                    }
                    if (rs.getInt("rejeitados") > 0) {
                        p.add(n + ": " + rs.getInt("rejeitados") + " evento(s) recusado(s) em /fila/rejeitados");
                    }
                    return n;
                }).list();
        return p;
    }

    @Scheduled(cron = "${crm.alerta-cron:0 * * * * *}")
    void verificar() {
        List<String> p = problemas();
        p.forEach(log::error);
        if (props.alertaUrl() == null) return;
        // convenção do healthchecks.io: GET <url> = tudo bem; POST <url>/fail com o motivo = alerta
        HttpRequest req = p.isEmpty()
                ? HttpRequest.newBuilder(props.alertaUrl()).GET().timeout(Duration.ofSeconds(10)).build()
                : HttpRequest.newBuilder(URI.create(props.alertaUrl() + "/fail"))
                        .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", p)))
                        .timeout(Duration.ofSeconds(10)).build();
        try {
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 300) log.warn("serviço de alerta respondeu {}", status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("serviço de alerta inacessível: {}", e.toString()); // se persistir, o serviço alerta pela falta de ping
        }
    }
}
