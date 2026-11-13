package br.com.hrb.crm.ingestao;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.hrb.crm.CrmProps;
import tools.jackson.databind.JsonNode;

/** Converte os eventos do sidecar e grava no banco. Não tem lógica de atendimento. */
@Service
public class Adaptador {

    public enum Resultado { GRAVADA, DUPLICADA, DESCARTADA }

    /** Mídia recebida junto com a mensagem. */
    public record Midia(String mime, String sha256Declarado, InputStream conteudo) {}

    private static final Logger log = LoggerFactory.getLogger(Adaptador.class);

    private final JdbcClient db;
    private final Path midiaDir;

    Adaptador(JdbcClient db, CrmProps props) {
        this.db = db;
        this.midiaDir = props.midiaDir();
    }

    /**
     * Grava a mensagem crua do Baileys recebida no número {@code numero}.
     * Idempotente por (número, ID externo).
     *
     * @param midia      null se a mensagem não tem mídia ou o download falhou
     * @param midiaErro  motivo da falha de download, quando houve
     */
    @Transactional
    public Resultado gravarMensagem(String numero, JsonNode bruto, Midia midia, String midiaErro) {
        travar();
        Optional<Long> numeroId = db.sql("select id from numero_whatsapp where telefone = ? and ativo")
                .param(numero).query(Long.class).optional();
        if (numeroId.isEmpty()) {
            log.warn("evento de número fora dos números de trabalho: {}", numero);
            return Resultado.DESCARTADA;
        }
        Optional<MensagemWhatsapp> convertida = MensagemWhatsapp.de(bruto);
        if (convertida.isEmpty()) return Resultado.DESCARTADA; // grupo, transmissão, status, canal
        MensagemWhatsapp m = convertida.get();
        boolean entreNumerosDeTrabalho = db.sql("select exists (select 1 from numero_whatsapp where telefone = ? or lid = ?)")
                .params(m.telefone(), m.lid()).query(Boolean.class).single();
        if (entreNumerosDeTrabalho) return Resultado.DESCARTADA; // vendedor com vendedor, ou consigo mesmo

        long contatoId = contato(m.lid(), m.telefone(), m.nome());
        Optional<Long> mensagemId = db.sql("""
                insert into mensagem (numero_whatsapp_id, contato_id, id_externo, direcao, tipo, texto, data, midia_erro, bruto)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (numero_whatsapp_id, id_externo) do nothing
                returning id""")
                .params(numeroId.get(), contatoId, m.idExterno(), m.deMim() ? "SAIDA" : "ENTRADA",
                        m.tipo(), m.texto(), Timestamp.from(m.data()), midiaErro, bruto.toString())
                .query(Long.class).optional();
        if (mensagemId.isEmpty()) return Resultado.DUPLICADA;

        if (midia != null) salvarMidia(mensagemId.get(), midia);
        if (midiaErro != null) log.warn("mensagem {} sem mídia: {}", m.idExterno(), midiaErro);
        return Resultado.GRAVADA;
    }

    /** Evento lid-mapping.update do Baileys. */
    @Transactional
    public void mapearLid(String lid, String pnJid) {
        if (!lid.endsWith("@lid") || !pnJid.endsWith("@s.whatsapp.net")) {
            throw new IllegalArgumentException("mapa @lid inválido: " + lid + " / " + pnJid);
        }
        travar();
        contato(MensagemWhatsapp.usuario(lid) + "@lid", MensagemWhatsapp.usuario(pnJid), null);
    }

    /**
     * Evento connection.update do Baileys (open, connecting, close).
     * @param lid @lid do próprio número, que o sidecar manda quando conecta; pode ser null
     */
    public void estadoSessao(String numero, String estado, String lid) {
        if (lid != null && !lid.endsWith("@lid")) throw new IllegalArgumentException("lid inválido: " + lid);
        db.sql("update numero_whatsapp set estado_sessao = ?, estado_em = now(), lid = coalesce(?, lid) where telefone = ?")
                .params(estado, lid == null ? null : MensagemWhatsapp.usuario(lid) + "@lid", numero).update();
    }

    /** Pulso periódico do sidecar, que vem pela mesma fila dos eventos. */
    public void pulso(String numero, int rejeitados) {
        db.sql("update numero_whatsapp set pulso_em = now(), rejeitados = ? where telefone = ?")
                .params(rejeitados, numero).update();
    }

    // ponytail: trava global por transação; o volume é de 2 números. Trava por contato se um dia pesar.
    private void travar() {
        db.sql("select pg_advisory_xact_lock(1)").query().singleRow();
    }

    /**
     * Acha o contato pelo @lid ou pelo telefone e completa o que faltar.
     * Se o mesmo cliente já existia em duas linhas (uma só com @lid, outra só com telefone), junta as duas.
     */
    private long contato(String lid, String telefone, String nome) {
        Optional<Long> porLid = lid == null ? Optional.empty()
                : db.sql("select id from contato where lid = ?").param(lid).query(Long.class).optional();
        Optional<Long> porTelefone = telefone == null ? Optional.empty()
                : db.sql("select id from contato where telefone = ?").param(telefone).query(Long.class).optional();

        long id;
        if (porLid.isPresent() && porTelefone.isPresent() && !porLid.equals(porTelefone)) {
            id = Math.min(porLid.get(), porTelefone.get());
            long sai = Math.max(porLid.get(), porTelefone.get());
            String nomeQueSai = (String) db.sql("select nome from contato where id = ?").param(sai).query().singleRow().get("nome");
            db.sql("update mensagem set contato_id = ? where contato_id = ?").params(id, sai).update();
            db.sql("delete from contato where id = ?").param(sai).update();
            if (nome == null) nome = nomeQueSai;
            log.info("contatos {} e {} juntados pelo mapa @lid/telefone", id, sai);
        } else if (porLid.isPresent() || porTelefone.isPresent()) {
            id = porLid.orElseGet(porTelefone::get);
        } else {
            return db.sql("insert into contato (lid, telefone, nome) values (?, ?, ?) returning id")
                    .params(lid, telefone, nome).query(Long.class).single();
        }
        db.sql("update contato set lid = coalesce(lid, ?), telefone = coalesce(telefone, ?), nome = coalesce(?, nome), visto_em = now() where id = ?")
                .params(lid, telefone, nome, id).update();
        return id;
    }

    /** Guarda o arquivo pelo próprio hash (aa/bb/hash): o mesmo arquivo recebido duas vezes ocupa um lugar só. */
    private void salvarMidia(long mensagemId, Midia midia) {
        try {
            Files.createDirectories(midiaDir);
            Path temp = Files.createTempFile(midiaDir, "recebendo-", null);
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long tamanho;
                try (InputStream in = new DigestInputStream(midia.conteudo(), sha)) {
                    tamanho = Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                String hash = HexFormat.of().formatHex(sha.digest());
                if (!hash.equalsIgnoreCase(midia.sha256Declarado())) {
                    throw new MidiaCorrompida("sha256 da mídia não confere com o declarado pelo sidecar");
                }
                String caminho = hash.substring(0, 2) + "/" + hash.substring(2, 4) + "/" + hash;
                Path destino = midiaDir.resolve(caminho);
                Files.createDirectories(destino.getParent());
                Files.move(temp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                db.sql("insert into midia (mensagem_id, mime, caminho, sha256, tamanho) values (?, ?, ?, ?, ?)")
                        .params(mensagemId, midia.mime(), caminho, hash, tamanho).update();
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // desfaz a transação; o sidecar reenvia
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class MidiaCorrompida extends RuntimeException {
        MidiaCorrompida(String msg) { super(msg); }
    }
}
