package br.com.hrb.crm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Apaga mensagens, mídia e contatos mais velhos que crm.retencao-dias. */
@Component
class Retencao {

    private static final Logger log = LoggerFactory.getLogger(Retencao.class);

    private final JdbcClient db;
    private final CrmProps props;

    Retencao(JdbcClient db, CrmProps props) {
        this.db = db;
        this.props = props;
    }

    // ponytail: sem transação. Se cair entre o banco e o disco, sobra arquivo órfão (nunca falta arquivo);
    // uma varredura de órfãos resolve se um dia aparecer.
    @Scheduled(cron = "${crm.retencao-cron:0 0 3 * * *}")
    int apagar() {
        Timestamp limite = Timestamp.from(Instant.now().minus(Duration.ofDays(props.retencaoDias())));
        List<String> caminhos = db.sql("""
                select distinct d.caminho from midia d join mensagem m on m.id = d.mensagem_id where m.data < ?""")
                .param(limite).query(String.class).list();
        int mensagens = db.sql("delete from mensagem where data < ?").param(limite).update(); // midia cai em cascata
        int contatos = db.sql("delete from contato c where not exists (select 1 from mensagem m where m.contato_id = c.id)").update();

        int arquivos = 0;
        for (String caminho : caminhos) {
            boolean aindaUsado = db.sql("select exists (select 1 from midia where caminho = ?)")
                    .param(caminho).query(Boolean.class).single();
            if (aindaUsado) continue;
            try {
                if (Files.deleteIfExists(props.midiaDir().resolve(caminho))) arquivos++;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        log.info("retenção de {} dias: {} mensagens, {} contatos e {} arquivos apagados",
                props.retencaoDias(), mensagens, contatos, arquivos);
        return mensagens;
    }
}
