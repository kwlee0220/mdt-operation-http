package mdt.operation.http.skku;

import java.io.File;
import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "skku")
@Getter @Setter
public class SKKUSimulatorConfiguration {
	private @Nullable String simulatorEndpoint;
	private String simulationSubmodelRefString;
	private File workingDirectory;
	private List<String> command;
	private @Nullable Duration timeout;
	private @Nullable Duration sessionRetainTimeout;
	
	public void setTimeout(String durStr) {
		timeout = Duration.parse(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		sessionRetainTimeout = Duration.parse(durStr);
	}
}
