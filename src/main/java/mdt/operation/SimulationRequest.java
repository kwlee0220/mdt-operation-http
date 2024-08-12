package mdt.operation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import lombok.Builder;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Builder
public class SimulationRequest {
	private final String modelId;
	private final Map<String,String> inputValues;
	private final List<String> outputVariableNames;
	private final Duration simulationTimeout;
	
	public String getModelId() {
		return modelId;
	}
	
	public Map<String, String> getInputValues() {
		return inputValues;
	}
	
	public List<String> getOutputVariableNames() {
		return outputVariableNames;
	}
	
	public Duration getSimulationTimeout() {
		return simulationTimeout;
	}
}
