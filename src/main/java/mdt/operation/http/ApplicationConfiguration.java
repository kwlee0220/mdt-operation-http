package mdt.operation.http;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import mdt.client.HttpMDTManagerClient;
import mdt.operation.http.skku.SKKUSimulatorConfiguration;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
public class ApplicationConfiguration {
	@Value("${mdt-manager.endpoint}")
	private String m_mdtEndpoint;

	@Bean
	HttpMDTManagerClient getMDTManagerClient() throws KeyManagementException, NoSuchAlgorithmException {
		return HttpMDTManagerClient.connect(m_mdtEndpoint);
	}
	
	@Bean
	@ConfigurationProperties(prefix = "operation-server")
	OperationServerConfiguration getOperationServerConfiguration() {
		return new OperationServerConfiguration();
	}
	
	@Bean
	@ConfigurationProperties(prefix = "skku")
	SKKUSimulatorConfiguration getMDTSimulatorConfiguration() {
		return new SKKUSimulatorConfiguration();
	}
}
