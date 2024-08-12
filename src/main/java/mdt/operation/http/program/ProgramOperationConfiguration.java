package mdt.operation.http.program;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Sets;

import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class ProgramOperationConfiguration {
	@Getter @Setter
	public static class PortParameters {
		private Set<String> inputs = Sets.newHashSet();
		private Set<String> outputs = Sets.newHashSet();
		private Set<String> inoutputs = Sets.newHashSet();
	}
	
	private String id;
	private List<String> command;
	private File workingDirectory;
	private boolean runAsync = false;
	
	private PortParameters portParameters = new PortParameters();
	private Set<String> optionParameters = Sets.newHashSet();
	@Nullable private Duration timeout;
	@Nullable private Duration sessionRetainTimeout;
	
	public void setTimeout(String durStr) {
		timeout = Duration.parse(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		sessionRetainTimeout = Duration.parse(durStr);
	}
}
