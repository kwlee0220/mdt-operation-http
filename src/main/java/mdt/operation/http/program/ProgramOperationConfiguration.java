package mdt.operation.http.program;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;

import lombok.Getter;
import lombok.Setter;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class ProgramOperationConfiguration {
	private String id;
	private List<String> command;
	private File workingDirectory;
	private boolean runAsync = false;
	private boolean addPortFileToCommandLine = true;
	
	private PortParameters portParameters;
	private Set<String> optionParameters = Sets.newHashSet();
	@Nullable private Duration timeout;
	@Nullable private Duration sessionRetainTimeout;
	
	public void setTimeout(String durStr) {
		timeout = Duration.parse(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		sessionRetainTimeout = Duration.parse(durStr);
	}
	
	public static class PortParameters {
		@JsonProperty("inputs") private final Set<String> m_inputs;
		@JsonProperty("outputs") private final Set<String> m_outputs;
		
		@JsonCreator
		public PortParameters(@JsonProperty("inputs") Collection<String> inputs,
								@JsonProperty("outputs") Collection<String> outputs) {
			m_inputs = Sets.newHashSet(inputs);
			m_outputs = Sets.newHashSet(outputs);
		}
		
		public Set<String> getInputs() {
			return m_inputs;
		}
		
		public Set<String> getOutputs() {
			return m_outputs;
		}
	}
}
