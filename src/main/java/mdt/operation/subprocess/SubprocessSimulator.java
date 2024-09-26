package mdt.operation.subprocess;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.Lists;

import utils.io.FileUtils;
import utils.io.IOUtils;

import mdt.operation.MDTSimulator;
import mdt.operation.SimulationRequest;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class SubprocessSimulator implements MDTSimulator {
	private final File m_workingDirectory;
	private final List<String> m_command;
	private final AtomicReference<Process> m_proc = new AtomicReference<>();
	
	public SubprocessSimulator(File workingDir, List<String> command) {
		m_command = command;
		m_workingDirectory = workingDir;
		if ( m_workingDirectory != null ) {
			m_workingDirectory.mkdirs();
		}
	}

	@Override
	public List<String> run(SimulationRequest request) throws IOException, InterruptedException,
																TimeoutException, Exception {
		List<String> commandLine = Lists.newArrayList(m_command);
		
		Map<String,String> inputs = request.getInputValues();
		for ( String inputId: inputs.keySet() ) {
			String argValue = inputs.get(inputId);
			File argFile = new File(m_workingDirectory, inputId);
			
			try ( FileWriter fw = new FileWriter(argFile) ) {
				fw.append(argValue);
			}
			commandLine.add(argFile.getName());
		}
		
		List<String> outputVars = request.getOutputVariableNames();
		for ( int i =0; i < outputVars.size(); ++i ) {
			String outputVar = outputVars.get(i);
			File outputFile = new File(m_workingDirectory, outputVar);
			FileUtils.touch(outputFile, false);
			
			commandLine.add(outputFile.getName());
		}

		ProcessBuilder builder = new ProcessBuilder(commandLine);
		if ( m_workingDirectory != null ) {
			builder.directory(m_workingDirectory);
		}
		
		Process process = builder.start();
		m_proc.set(process);
		if ( request.getSimulationTimeout() != null ) {
			if ( process.waitFor(request.getSimulationTimeout().toMillis(), TimeUnit.MILLISECONDS) ) {
				m_proc.set(null);
				List<String> outputs = collectOutputs(request);
				cleanArgFiles(request);
				return outputs;
			}
			else {
				m_proc.set(null);
				process.destroyForcibly();
				throw new TimeoutException(request.getSimulationTimeout().toString());
			}
		}
		else {
			int retCode = process.waitFor();
			m_proc.set(null);
			if ( retCode == 0 ) {
				List<String> outputs = collectOutputs(request);
				cleanArgFiles(request);
				return outputs;
			}
			else {
				throw new Exception("Simulation failed");
			}
		}
	}
	
	public void cancel() {
		Process proc = m_proc.get();
		if ( proc != null ) {
			proc.destroy();
		}
	}
	
	private List<String> collectOutputs(SimulationRequest request) throws IOException {
		List<String> outputs = Lists.newArrayList();
		for ( String outputVar: request.getOutputVariableNames() ) {
			File outputFile = new File(m_workingDirectory, outputVar);
			outputs.add(IOUtils.toString(outputFile));
		}
		return outputs;
	}
	
	private void cleanArgFiles(SimulationRequest request) {
		for ( String inputId: request.getInputValues().keySet() ) {
			File inputFile = new File(m_workingDirectory, inputId);
			inputFile.delete();
		}
		for ( String outputVar: request.getOutputVariableNames() ) {
			File outputFile = new File(m_workingDirectory, outputVar);
			outputFile.delete();
		}
	}
}
