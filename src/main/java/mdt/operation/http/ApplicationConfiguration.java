package mdt.operation.http;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import mdt.client.MDTClientConfig;
import mdt.client.instance.HttpMDTInstanceManagerClient;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
public class ApplicationConfiguration {
	@Bean
	@ConfigurationProperties(prefix = "mdt-server")
	MDTClientConfig getMDTClientConfig() {
		return new MDTClientConfig();
	}

	@Bean
	HttpMDTInstanceManagerClient getMDTInstanceManagerClient() throws KeyManagementException,
																	NoSuchAlgorithmException {
		return HttpMDTInstanceManagerClient.connect(getMDTClientConfig());
	}
	
	@Bean
	@ConfigurationProperties(prefix = "operation-server")
	OperationServerConfiguration getOperationServerConfiguration() {
		return new OperationServerConfiguration();
	}
}
