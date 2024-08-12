package mdt.operation.http.junk;

import java.io.File;
import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class MDTSimulatorConfiguration {
	private String endpoint;
	private String submodelId;
	private File workingDirectory;
	private List<String> commandPrefix;
	@Nullable private Duration timeout;
	@Nullable private Duration sessionRetainTimeout;
	
	public void setTimeout(String durStr) {
		timeout = Duration.parse(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		sessionRetainTimeout = Duration.parse(durStr);
	}
}
