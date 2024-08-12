package mdt.operation.http.junk;

import java.io.File;
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

import utils.InternalException;
import utils.async.Execution;
import utils.async.Guard;
import utils.async.StartableExecution;
import utils.async.op.AsyncExecutions;
import utils.func.KeyValue;
import utils.io.FileUtils;
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
//@RestController
//@RequestMapping("")
public class MDTProgramOperationServerController2 implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTProgramOperationServerController2.class);
	
	@Autowired private ProgramOperationConfiguration m_config;
	
	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private ProcessBasedMDTOperation m_op;

	@Override
	public void afterPropertiesSet() throws Exception {
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
		
		if ( s_logger.isInfoEnabled() ) {
			File workDir = m_config.getWorkingDirectory();
			if ( workDir == null ) {
				workDir = FileUtils.getWorkingDirectory();
			}
			s_logger.info("ProgramOperation's working directory: {}", workDir);
			
			s_logger.info("Operation Timeout: {}", m_config.getTimeout());
			s_logger.info("Operation SessionRetainTimeout: {}", m_config.getSessionRetainTimeout());
		}
	}

    @PostMapping("/{opId}")
    public ResponseEntity<OperationResponse<JsonNode>> run(@PathVariable("opId") String opId,
    														@RequestBody String parametersJson) {
    	Builder builder = ProcessBasedMDTOperation.builder()
													.setCommand(m_config.getCommand())
													.setWorkingDirectory(m_config.getWorkingDirectory())
													.setTimeout(m_config.getTimeout());

    	Map<String,String> parameters = HttpOperationClient.parseParametersJson(parametersJson);

		// option 정보를 command line의 option으로 추가시킨다.
    	FStream.from(m_config.getOptionParameters())
    			.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addOption(param.key(), param.value()));
    	
    	FStream.from(m_config.getPortParameters().getInputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), false));
    	
    	FStream.from(m_config.getPortParameters().getOutputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), true));
    	
    	FStream.from(m_config.getPortParameters().getInoutputs())
				.map(name -> KeyValue.of(name, parameters.get(name)))
    			.filter(kv -> kv.value() != null)
    			.fold(builder, (b, param) -> b.addFileArgument(param.key(), param.value(), true));
    	
    	ProcessBasedMDTOperation op = m_guard.getOrThrow(() -> {
    		if ( m_op != null ) {
    			throw new IllegalStateException();
    		}
    		return m_op = builder.build();
    	});
    	
    	if ( m_config.isRunAsync() ) {
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
    	
    	op.start();
    	op.whenFinished(result -> {
    		OperationResponse<JsonNode> resp = new OperationResponse<JsonNode>();
			result.ifSuccessful(outputs -> {
				// 시뮬레이션이 성공한 경우.
				// 수행 결과 인자를 변경시킨다.
		    	if ( outputs != null ) {
		    		JsonNode jnode = HttpOperationClient.buildParametersJson(outputs);
		    		resp.setResult(jnode);
		    	}
			});
			
			StartableExecution<Void> delayedSessionClose
	    			= AsyncExecutions.delayed(() -> unsetOperation(), m_config.getSessionRetainTimeout());
			delayedSessionClose.start();
		});
		
		OperationResponse<JsonNode> resp = buildOperationResponse(op);
		try {
			URI location = new URI(m_config.getId());
			return ResponseEntity.created(location).body(resp);
		}
		catch ( URISyntaxException e ) {
			throw new InternalException("invalid 'Location': " + m_config.getId());
		}
    }

    @GetMapping("")
    @ResponseStatus(HttpStatus.OK)
    public OperationResponse<JsonNode> status() {
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
    			return buildOperationResponse(m_op);
    		}
    	}
    	finally {
    		m_guard.unlock();
    	}
    }

    @DeleteMapping("")
    @ResponseStatus(HttpStatus.OK)
    public OperationResponse<JsonNode> delete() {
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
	
	private void shutdown() {
		if ( s_logger.isInfoEnabled() ) {
			s_logger.info("Shutting down HTTP-based OperationServer...");
		}
		
		ProcessBasedMDTOperation op = m_op;
		if ( op != null ) {
			op.cancel(true);
		}
	}
}
