package mdt.operation.http;

import java.net.URI;
import java.net.URISyntaxException;
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
import utils.async.Execution;
import utils.async.Guard;
import utils.async.StartableExecution;
import utils.async.op.AsyncExecutions;
import utils.func.KeyValue;
import utils.stream.FStream;

import mdt.client.instance.HttpMDTInstanceManagerClient;
import mdt.client.operation.HttpOperationClient;
import mdt.client.operation.OperationResponse;
import mdt.client.operation.OperationStatus;
import mdt.client.operation.ProcessBasedMDTOperation;
import mdt.client.operation.ProcessBasedMDTOperation.Builder;
import mdt.operation.http.program.ProgramOperationConfiguration;
import mdt.operation.http.skku.SKKUSimulatorConfiguration;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping("")
public class MDTOperationServerController implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationServerController.class);
	private static final JsonMapper MAPPER = new JsonMapper();
	
	@Autowired private HttpMDTInstanceManagerClient m_manager;
	@Autowired private OperationServerConfiguration m_config;
	private final Map<String,Object> m_opConfigs = Maps.newHashMap();

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private ProcessBasedMDTOperation m_op;
	
	@Override
	public void afterPropertiesSet() throws Exception {
		JsonNode serverDescs = MAPPER.readTree(m_config.getOperations());
		for ( Map.Entry<String,JsonNode> ent: serverDescs.properties() ) {
			if ( s_logger.isInfoEnabled() ) {
				s_logger.info("loading operation: {} (id={})", ent.getValue().at("/id").asText(),  ent.getKey());
			}
			
			switch ( ent.getKey() ) {
				case "skku-simulator":
					SKKUSimulatorConfiguration skConf = MAPPER.readValue(ent.getValue().traverse(),
																		SKKUSimulatorConfiguration.class);
					m_opConfigs.put(skConf.getId(), skConf);
					break;
				case "program":
					ProgramOperationConfiguration poConf = MAPPER.readValue(ent.getValue().traverse(),
																		ProgramOperationConfiguration.class);
					m_opConfigs.put(poConf.getId(), poConf);
					break;
			}
		}
	}

    @PostMapping("/{opId}")
    public ResponseEntity<OperationResponse<JsonNode>> run(@PathVariable("opId") String opId,
    														@RequestBody String parametersJson) {
    	ProgramOperationConfiguration conf = (ProgramOperationConfiguration)m_opConfigs.get(opId);
    	
    	Builder builder = ProcessBasedMDTOperation.builder()
													.setCommand(conf.getCommand())
													.setWorkingDirectory(conf.getWorkingDirectory())
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
    	
    	FStream.from(conf.getPortParameters().getInoutputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), true));
    	
    	ProcessBasedMDTOperation op = m_guard.getOrThrow(() -> {
    		if ( m_op != null ) {
    			throw new IllegalStateException();
    		}
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
		    			= AsyncExecutions.delayed(() -> unsetOperation(), conf.getSessionRetainTimeout());
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
				m_op = null;
			}
		});
    }
}
