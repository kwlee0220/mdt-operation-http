package mdt.operation.http;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Maps;

import utils.InternalException;
import utils.KeyValue;
import utils.Utilities;
import utils.async.Execution;
import utils.async.Guard;
import utils.async.StartableExecution;
import utils.async.op.AsyncExecutions;
import utils.func.FOption;
import utils.stream.FStream;

import mdt.client.operation.HttpOperationClient;
import mdt.client.operation.OperationResponse;
import mdt.client.operation.OperationStatus;
import mdt.client.operation.ProcessBasedMDTOperation;
import mdt.client.operation.ProcessBasedMDTOperation.Builder;
import mdt.operation.http.program.ProgramOperationConfiguration;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping("operations")
public class MDTOperationServerController implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationServerController.class);
	private static final JsonMapper MAPPER = new JsonMapper();
	
//	@Autowired private HttpMDTInstanceManagerClient m_manager;
	@Autowired private OperationServerConfiguration m_config;
	private final Map<String,Object> m_opConfigs = Maps.newHashMap();

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private ProcessBasedMDTOperation m_op;
	
	@Override
	public void afterPropertiesSet() throws Exception {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("loading operations from {}", m_config.getOperations().getAbsolutePath());
		}
		
		File homeDir = FOption.getOrElse(m_config.getHomeDir(), () -> m_config.getOperations()
																				.getAbsoluteFile()
																				.getParentFile());
		
		JsonNode serverDescs = MAPPER.readTree(m_config.getOperations());
		for ( Map.Entry<String,JsonNode> ent: serverDescs.properties() ) {
			if ( ent.getKey().equals("program") ) {
				Iterator<JsonNode> opConfNodeIter = ent.getValue().iterator();
				while ( opConfNodeIter.hasNext() ) {
					String confStr = MAPPER.writeValueAsString(opConfNodeIter.next());
					ProgramOperationConfiguration poConf = MAPPER.readValue(confStr, ProgramOperationConfiguration.class);
					
					File opHome = new File(homeDir, poConf.getId());
					Map<String,String> vars = Maps.newHashMap(System.getenv());
					vars.put("MDT_OPERATION_SERVER_HOME", homeDir.getAbsolutePath());
					vars.put("MDT_OPERATION_HOME", opHome.getAbsolutePath());
					confStr = Utilities.substributeString(confStr, vars);
					poConf = MAPPER.readValue(confStr, ProgramOperationConfiguration.class);
					
					if ( s_logger.isInfoEnabled() ) {
						s_logger.info("\tloading program operation: id={}, dir={}",
										poConf.getId(), opHome);
					}
					m_opConfigs.put(poConf.getId(), poConf);
				}	
				break;
			}
		}
	}

    @PostMapping("/{opId}")
    public ResponseEntity<OperationResponse<JsonNode>> run(@PathVariable("opId") String opId,
    														@RequestBody String parametersJson) {
    	if ( s_logger.isDebugEnabled() ) {
    		s_logger.debug("Trying to start an operation: {}", opId);
    	}
    	
    	ProgramOperationConfiguration conf = (ProgramOperationConfiguration)m_opConfigs.get(opId);
    	if ( conf == null ) {
    		String msg = String.format("Unknown operation: id=%s", opId);
			return ResponseEntity.internalServerError()
									.body(OperationResponse.<JsonNode>failed(msg));
    	}
    	if ( s_logger.isInfoEnabled() ) {
    		String inputs = FStream.from(conf.getPortParameters().getInputs()).join(',');
    		String outputs = FStream.from(conf.getPortParameters().getOutputs()).join(',');
    		String workingDirStr = FOption.mapOrElse(conf.getWorkingDirectory(),
    												f -> String.format(", working-dir=%s", f), "");
    		
    		s_logger.info("Starting an operation: id={}, command={}{}, inputs={{}}, outputs={{}}",
    						opId, conf.getCommand(), workingDirStr, inputs, outputs);
    	}
    	
    	Builder builder = ProcessBasedMDTOperation.builder()
													.setCommand(conf.getCommand())
													.setWorkingDirectory(conf.getWorkingDirectory())
													.addPortFileToCommandLine(conf.isAddPortFileToCommandLine())
													.setTimeout(conf.getTimeout());

    	Map<String,String> parameters = HttpOperationClient.parseParametersJson(parametersJson);

		// option 정보를 command line의 option으로 추가시킨다.
    	FStream.from(conf.getOptionParameters())
    			.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addOption(param.key(), param.value()));
    	
    	FStream.from(conf.getPortParameters().getInputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), false));
    	
    	FStream.from(conf.getPortParameters().getOutputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), true));
    	
    	ProcessBasedMDTOperation op = m_guard.getOrThrow(() -> {
//    		if ( m_op != null ) {
//    			throw new IllegalStateException();
//    		}
    		return m_op = builder.build();
    	});
    	
    	if ( !conf.isRunAsync() ) {
    		try {
				Map<String,String> outputs = m_op.run();
	    		JsonNode jnode = HttpOperationClient.buildParametersJson(outputs);
	    		OperationResponse<JsonNode> resp = OperationResponse.<JsonNode>completed(jnode);
	    		return ResponseEntity.ok(resp);
			}
			catch ( Exception e ) {
				return ResponseEntity.internalServerError()
										.body(OperationResponse.<JsonNode>failed("" + e));
										
			}
    	}
    	
    	op.whenFinished(result -> {
    		OperationResponse<JsonNode> resp = new OperationResponse<JsonNode>();
			result.ifSuccessful(outputs -> {
				// 시뮬레이션이 성공한 경우.
				// 수행 결과 인자를 변경시킨다.
		    	if ( outputs != null ) {
		    		JsonNode jnode = HttpOperationClient.buildParametersJson(outputs);
		    		resp.setResult(jnode);
		    	}
				
				StartableExecution<Void> delayedSessionClose
		    			= AsyncExecutions.delayed(this::unsetOperation, conf.getSessionRetainTimeout());
				delayedSessionClose.start();
			});
		});
    	op.start();
		
		OperationResponse<JsonNode> resp = buildOperationResponse(op);
		try {
			return ResponseEntity.created(new URI("")).body(resp);
		}
		catch ( URISyntaxException e ) {
			throw new InternalException("invalid 'Location': " + conf.getId());
		}
    }

    @GetMapping("/{opId}")
    @ResponseStatus(HttpStatus.OK)
    public OperationResponse<JsonNode> status(@PathVariable("opId") String opId) {
    	m_guard.lock();
    	try {
    		if ( m_op == null ) {
        		String msg = String.format("Operation is not found");
        		return OperationResponse.<JsonNode>builder()
    									.status(OperationStatus.FAILED)
    									.message(msg)
    									.build();
    		}
    		else {
    			OperationResponse<JsonNode> resp = buildOperationResponse(m_op);
    	    	if ( s_logger.isInfoEnabled() ) {
    	    		s_logger.info("response to status: {}", resp);
    	    	}
    	    	
    	    	return resp;
    		}
    	}
    	finally {
    		m_guard.unlock();
    	}
    }

    @DeleteMapping("/{opId}")
    @ResponseStatus(HttpStatus.OK)
    public OperationResponse<JsonNode> delete(@PathVariable("opId") String opId) {
    	m_guard.lock();
    	try {
    		if ( m_op == null ) {
        		String msg = String.format("Operation is not found");
        		return OperationResponse.<JsonNode>builder()
    									.status(OperationStatus.FAILED)
    									.message(msg)
    									.build();
    		}
    		else {
    			m_op.cancel(true);
    			return buildOperationResponse(m_op);
    		}
    	}
    	finally {
    		m_guard.unlock();
    	}
    }
	
    private OperationResponse<JsonNode> buildOperationResponse(Execution<Map<String,String>> op) {
    	OperationResponse<JsonNode> resp = new OperationResponse<JsonNode>();
    	
    	OperationStatus status = toOperationStatus(op);
    	resp.setStatus(status);
    	switch ( status ) {
    		case RUNNING:
    		case CANCELLED:
    		case FAILED:
    			break;
    		case COMPLETED:
        		JsonNode jnode = HttpOperationClient.buildParametersJson(op.getUnchecked());
        		resp.setResult(jnode);
    	}
    	
    	resp.setMessage(getStatusMessage(op));
    	
    	return resp;
    }
    
    private String getStatusMessage(Execution<?> op) {
    	return switch ( op.getState() ) {
    		case STARTING -> "Operation is starting";
    		case RUNNING -> "Operation is running";
    		case COMPLETED -> "Operation is completed";
    		case CANCELLING -> "Operation is cancelling";
    		case CANCELLED -> "Operation is cancelled";
    		case FAILED -> "" + op.poll().getFailureCause();
    		default -> throw new IllegalStateException("Unexpected Simulation state: " + op.getState());
    	};
    }

	private OperationStatus toOperationStatus(Execution<?> exec) {
    	try {
    		exec.get(0, TimeUnit.MILLISECONDS);
			return OperationStatus.COMPLETED;
		}
		catch ( CancellationException e ) {
			return OperationStatus.CANCELLED;
		}
		catch ( TimeoutException e ) {
			return OperationStatus.RUNNING;
		}
		catch ( InterruptedException e ) {
			throw new InternalException("" + e);
		}
		catch ( ExecutionException e ) {
			return OperationStatus.FAILED;
		}
	}
    
    private void unsetOperation() {
		m_guard.run(() -> {
			if ( m_op != null ) {
				if ( s_logger.isDebugEnabled() ) {
					s_logger.debug("purge the finished operation: {}", m_op);
				}
				m_op = null;
			}
		});
    }
}
