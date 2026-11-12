package br.com.hrb.crm;

import java.net.URI;
import java.nio.file.Path;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Sem valor padrão de propósito: a aplicação não sobe sem segredo, pasta de mídia e prazo de retenção. */
@Validated
@ConfigurationProperties("crm")
public record CrmProps(
        @NotNull @Size(min = 32) String sidecarSegredo,
        @NotNull Path midiaDir,
        @NotNull @Positive Integer retencaoDias,
        /** URL de ping do healthchecks.io (ou compatível). Sem ela, os alertas só vão para o log. */
        URI alertaUrl,
        @DefaultValue("5") @Positive int alertaSessaoMinutos,
        @DefaultValue("10") @Positive int alertaPulsoMinutos) {
}
