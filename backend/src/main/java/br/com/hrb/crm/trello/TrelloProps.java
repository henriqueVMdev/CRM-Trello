package br.com.hrb.crm.trello;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Só existe com crm.trello.segredo-app configurado: sem ele (fase 0 e 1) não há rota do Trello nem cliente.
 * token e segredo-app chegam como segredo (/run/secrets/crm.trello.token e crm.trello.segredo-app).
 */
@Validated
@ConfigurationProperties("crm.trello")
@ConditionalOnProperty(prefix = "crm.trello", name = "segredo-app")
public record TrelloProps(
        /** chave da API (não é segredo) */
        @NotBlank String chave,
        /** token do usuário dedicado do sync, nunca de conta pessoal (regra 13) */
        @NotBlank String token,
        /** segredo do app, assina o webhook */
        @NotBlank String segredoApp,
        /** exatamente como foi registrado no webhook: entra na assinatura */
        @NotBlank String callbackUrl,
        /** ID de membro do usuário do sync: ação dele no quadro é eco do próprio sync */
        @NotBlank String usuarioSyncId,
        @DefaultValue("https://api.trello.com/1") URI apiUrl,
        /** teto do corpo do webhook; o payload real do Trello fica em poucos KB */
        @DefaultValue("256KB") DataSize corpoMax) {
}
