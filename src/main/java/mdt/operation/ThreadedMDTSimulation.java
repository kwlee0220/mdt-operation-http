package mdt.operation;

import java.util.List;
import java.util.concurrent.CancellationException;

import utils.async.AbstractThreadedExecution;
import utils.async.CancellableWork;
import utils.async.StartableExecution;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class ThreadedMDTSimulation extends AbstractThreadedExecution<List<String>>
									implements StartableExecution<List<String>>, CancellableWork {
	private final SimulationRequest m_request;
	private final MDTSimulator m_simulator;
	
	public ThreadedMDTSimulation(MDTSimulator simulator, SimulationRequest request) {
		m_simulator = simulator;
		m_request = request;
	}

	@Override
	protected List<String> executeWork() throws InterruptedException, CancellationException, Exception {
		return m_simulator.run(m_request);
	}
	
	@Override
	public boolean cancelWork() {
		m_simulator.cancel();
		return true;
	}
}
