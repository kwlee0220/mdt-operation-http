package mdt.operation;

import java.util.List;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public interface MDTSimulator {
	public List<String> run(SimulationRequest request) throws Exception;
	public void cancel();
}
