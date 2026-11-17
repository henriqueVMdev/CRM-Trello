package br.com.hrb.crm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Um Postgres para a JVM inteira: cada combinação de propriedades de teste sobe outro contexto Spring,
	 * e sem o static cada contexto subiria o próprio contêiner. Cada classe de teste limpa as tabelas que usa.
	 */
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	// Os contextos ficam no cache do Spring e só fecham no fim da JVM, então nenhum para o contêiner do outro.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

}
