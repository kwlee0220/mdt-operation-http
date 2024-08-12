package mdt.operation;

import java.time.Duration;
import java.util.List;

import utils.async.Execution;

import lombok.Builder;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Builder
public class SimulationSession {
	private final String sessionId;
	private final Execution<List<String>> simulation;
	private final String output;
	private final Duration sessionRetainTimeout;
	
	public String getSessionId() {
		return sessionId;
	}
	
	public Execution<List<String>> getSimulation() {
		return simulation;
	}
	
	public String getOutput() {
		return output;
	}
	
	public Duration getSessionRetainTimeout() {
		return sessionRetainTimeout;
	}
}
