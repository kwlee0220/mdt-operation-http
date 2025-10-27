package mdt.operation.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import mdt.operation.http.skku.SKKUSimulatorConfiguration;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
@EnableConfigurationProperties({
	OperationServerConfiguration.class,
	SKKUSimulatorConfiguration.class,
})
public class ApplicationConfiguration {
}
