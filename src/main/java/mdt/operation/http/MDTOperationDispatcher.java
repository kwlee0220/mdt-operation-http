package mdt.operation.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.concurrent.GuardedBy;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Maps;

import utils.InternalException;
import utils.Throwables;
import utils.async.Guard;
import utils.async.command.CommandExecution;
import utils.async.command.CommandVariable;
import utils.async.command.CommandVariable.FileVariable;
import utils.func.Either;
import utils.func.Optionals;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.io.IOUtils;
import utils.stream.KeyValueFStream;

import mdt.client.HttpMDTManager;
import mdt.client.operation.OperationRequest;
import mdt.client.operation.OperationResponse;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.operation.http.program.ProgramOperationConfiguration;
import mdt.task.TaskException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping(value={""})
public class MDTOperationDispatcher implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationDispatcher.class);
	private static final Duration SESSION_RETAIN_TIMEOUT = Duration.ofMinutes(5);
	
	private MDTInstanceManager m_manager;
	@Autowired private OperationServerConfiguration m_config;
	private File m_homeDir;

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private final Map<String,OperationSession> m_sessions = Maps.newHashMap();
	@GuardedBy("m_guard") private Cache<String,OperationSession> m_closedSessions
														= CacheBuilder.newBuilder()
//																		.expireAfterAccess(SESSION_RETAIN_TIMEOUT)
																		.expireAfterWrite(SESSION_RETAIN_TIMEOUT)
																		.removalListener(this::onClosedSessionExpired)
																		.build();

	@Override
	public void afterPropertiesSet() throws Exception {
		Preconditions.checkState(m_config != null);

		HttpMDTManager mdt = HttpMDTManager.connect(m_config.getInstanceManagerEndpoint());
		m_manager = mdt.getInstanceManager();
		
		// 설정 파일에 'homeDir'이 지정되지 않은 경우에는 'operations' 파일이 위치한
		// 디렉토리를 사용한다.
		m_homeDir = Optionals.getOrElse(m_config.getHomeDir(), FileUtils::getCurrentWorkingDirectory);
	}

    @PostMapping("/operations")
    public ResponseEntity<?> run(@RequestBody String requestJson)
    	throws TimeoutException, CancellationException, InterruptedException, ExecutionException, IOException {
    	OperationRequest request = OperationRequest.parseJsonString(requestJson);
    	Either<OperationSession, ResponseEntity<RESTfulErrorEntity>> result = start(request);
    	if ( result.isRight() ) {
    		return result.right().get();
    	}
    	
    	OperationSession session = result.left().get();
    	if ( session.m_request.isAsync() ) {
    		OperationResponse resp = OperationResponse.running(session.m_sessionId, "Operation is running");
    		try {
    			return ResponseEntity.created(new URI("")).body(resp);
    		}
    		catch ( URISyntaxException e ) {
    			throw new InternalException("invalid 'Location': " + session.m_opId);
    		}
    	}
    	else {
    		return awaitExecution(session);
    	}
    }
    
    private ResponseEntity<String> awaitExecution(OperationSession session)
    	throws ExecutionException, CancellationException, InterruptedException, TimeoutException {
    	ProgramOperationConfiguration config = session.getProgramOperationConfiguration();
		try {
			Duration timeout = config.getTimeout();
			if ( timeout != null ) {
				session.m_cmdExec.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			else {
				session.m_cmdExec.get();
			}
			
    		try {
				return buildResponse(session);
			}
			catch ( IOException e ) {
				throw new ExecutionException("Failed to update output port", e);
			}
		}
		finally {
			m_sessions.remove(session.getSessionId());
		}
    }

    @GetMapping("/sessions/{session}")
    public ResponseEntity<?> status(@PathVariable("session") String sessionId) throws IOException {
    	m_guard.lock();
    	try {
    		OperationSession session = m_sessions.get(sessionId);
    		
    		// Operation id에 해당하는 실행 등록 정보가 없는 경우에는 NOT_FOUND 오류를 발생시킨다.
    		if ( session == null ) {
    			// 작업은 종료되었지만, 클라이언트까지 결과가 전달되지 않은 경우
    			OperationSession closedSession = m_closedSessions.getIfPresent(sessionId);
    			if ( closedSession != null ) {
    	    		return buildResponse(closedSession);
    			}
    			else {
	    			String msg = "OperationSession is not found: session=" + closedSession;
	    			return ResponseEntity.status(HttpStatus.NOT_FOUND)
	    								.body(RESTfulErrorEntity.ofMessage(msg));
    			}
    		}
    		else {
    			// 작업이 수행 중인 경우
    			return buildResponse(session);
    		}
    	}
    	finally {
    		m_guard.unlock();
    	}
    }

    @DeleteMapping("/sessions/{session}")
    public ResponseEntity<Void> delete(@PathVariable("session") String sessionId) {
    	m_guard.lock();
    	try {
    		OperationSession opExec = m_sessions.remove(sessionId);
    		if ( opExec != null ) {
        		opExec.m_cmdExec.cancel(true);
    		}
    		
    		return ResponseEntity.status(HttpStatus.NO_CONTENT)
    								.build();
    	}
    	finally {
    		m_guard.unlock();
    	}
    }
    
    @ExceptionHandler()
    public ResponseEntity<RESTfulErrorEntity> handleException(Exception e) {
		Throwable cause = Throwables.unwrapThrowable(e);
		s_logger.error("Exception raised: " + e);
    	if ( cause instanceof IllegalArgumentException ) {
    		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof TimeoutException ) {
    		return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof ResourceNotFoundException ) {
    		return ResponseEntity.badRequest().body(RESTfulErrorEntity.of(cause));
    	}
    	else {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    }

    private Either<OperationSession, ResponseEntity<RESTfulErrorEntity>> start(OperationRequest request) {
		String opId = request.getOperation();
		
    	File opHome = new File(m_config.getOperationsDir(), opId);
    	if ( !opHome.isDirectory() ) {
    		s_logger.error("Invalid operation home directory: op={}, dir={}", opId, opHome);
    		
    		ResourceNotFoundException ex = new ResourceNotFoundException("Operation", "operation=" + opId);
			return Either.right(ResponseEntity.badRequest().body(RESTfulErrorEntity.of(ex)));
    	}
    	
    	OperationSession session = OperationSession.create(request);

		File opConfigFile = new File(opHome, "operation.json");
    	try {
    		ProgramOperationConfiguration opConfig = MDTModelSerDe.readValue(opConfigFile,
    																		ProgramOperationConfiguration.class);
    		session.setProgramOperationConfiguration(opConfig);
    		
    		if ( opConfig.getWorkingDirectory() == null ) {
    			opConfig.setWorkingDirectory(opHome);
    		}
    	}
    	catch ( IOException e ) {
    		String msg = String.format("Failed to read ProgramOperationConfiguration: path=%s, cause=%s",
    									opConfigFile, e);
    		s_logger.error(msg);
    		
    		return Either.right(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    											.body(RESTfulErrorEntity.ofMessage(msg)));
    	}
    
		try {
			CommandExecution cmdExec = buildCommandExecution(session);
			session.setCommandExecution(cmdExec);
		}
		catch ( TaskException e ) {
    		String msg = String.format("Failed to create CommandExecution: cause=%s", e);
    		s_logger.error(msg);
    		
    		return Either.right(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    											.body(RESTfulErrorEntity.ofMessage(msg)));
		}
    	
    	m_guard.lock();
    	try {
    		// 동시 연산 수행을 지원하지 않는 경우에는 동일 연산이 수행 중인 경우에 예외를 발생시킨다.
    		if ( !session.getProgramOperationConfiguration().isConcurrentExecution() ) {
    			boolean existsOp = KeyValueFStream.from(m_sessions)
					    					.filterValue(s -> s.m_opId.equals(opId))
					    					.exists();
    			if ( existsOp ) {
        			String msg = "Running operation exists: opId=" + opId;
        			return Either.right(ResponseEntity.status(HttpStatus.CONFLICT)
        												.body(RESTfulErrorEntity.ofMessage(msg)));
    			}
    		}
    		m_sessions.put(session.m_sessionId, session);
    		
    		// CommandExecution이 종료되면
    		session.m_cmdExec.whenFinished(result -> {
    			m_guard.lock();
    			try {
	    			OperationSession closed = m_sessions.remove(session.m_sessionId);
	    			if ( closed != null ) {
	        			try {
							updateOutputArguments(session);
						}
						catch ( Throwable e ) {
							s_logger.error("Failed to update output arguments: cause=" + e);
						}
						closed.close();
	    				m_closedSessions.put(session.m_sessionId, closed);
	    				s_logger.debug("add finished operation to closed-session list: session={}", session);
	    			}
    			}
    			finally {
    				m_guard.unlock();
    			}
    		});
    		
    		m_guard.signalAll();
    	}
    	finally {
    		m_guard.unlock();
    	}

    	session.m_cmdExec.start();
		return Either.left(session);
    }
    
    private ResponseEntity<String> buildResponse(OperationSession session) throws IOException {
    	String sessId = session.m_sessionId;
    	CommandExecution exec = session.m_cmdExec;
    	OperationResponse resp = switch ( exec.getState() ) {
    		case RUNNING -> OperationResponse.running(sessId, "Operation is running");
    		case COMPLETED -> OperationResponse.completed(sessId, session.m_request.getOutputArguments());
    		case FAILED -> OperationResponse.failed(sessId, exec.getResult().getCause());
    		case CANCELLED -> OperationResponse.cancelled(sessId, "Operation is cancelled");
    		case CANCELLING -> OperationResponse.cancelled(sessId, "Operation is cancelling");
    		case STARTING -> OperationResponse.cancelled(sessId, "Operation is starting");
    		case NOT_STARTED -> OperationResponse.cancelled(sessId, "Operation is not found");
    		default -> throw new InternalException("Unexpected execution status: " + exec.getState());
    	};
    	
    	return ResponseEntity.status(HttpStatus.OK).body(resp.toJsonString());
    }
    
    private void updateOutputArguments(OperationSession session) throws IOException {
    	KeyValueFStream.from(session.m_request.getOutputArguments())
						.match(session.m_cmdExec.getVariableMap())
						.forEachOrThrow(match -> {
							SubmodelElement arg = match.value()._1;
							CommandVariable cmdVar = match.value()._2;
							ElementValues.updateWithValueJsonString(arg, cmdVar.getValue());
						});
    }

	private CommandExecution buildCommandExecution(OperationSession session) throws TaskException {
		ProgramOperationConfiguration config = session.getProgramOperationConfiguration();
		File workingDir = config.getWorkingDirectory();
		
		CommandExecution.Builder builder = CommandExecution.builder()
															.addCommand(config.getCommandLine())
															.setWorkingDirectory(workingDir)
															.setTimeout(config.getTimeout());
		
		KeyValueFStream.from(session.m_request.getInputArguments())
						.mapOrThrow(kv -> newCommandVariable(workingDir, kv.key(), kv.value()))
						.forEachOrThrow(builder::addVariableIfAbscent);
		KeyValueFStream.from(session.m_request.getOutputArguments())
						.mapOrThrow(kv -> newCommandVariable(workingDir, kv.key(), kv.value()))
						.forEachOrThrow(builder::addVariableIfAbscent);

		// stdout/stderr redirection
		builder.redirectErrorStream();
		builder.redirectStdoutToFile(new File(workingDir, "output.log"));
		
		return builder.build();
	}
	
	private FileVariable newCommandVariable(File workingDir, String varId, SubmodelElement sme) throws TaskException {
		File file = null;
		try {
			ElementValue value = ElementValues.getValue(sme);
			
//			if ( value instanceof FileValue fv ) {
//				if ( variable instanceof ReferenceVariable refPort ) {
//					MDTElementReference dref = (MDTElementReference) refPort.getReference();
//
//					String fileName = String.format("%s.%s", varId, FilenameUtils.getExtension(fv.getValue()));
//					file = new File(workingDir, fileName);
//					dref.readAttachment(file);
//					
//					return new FileVariable(varId, file);
//				}
//				else {
//					throw new TaskException("TaskVariable should be a ReferenceVariable: var=" + variable);
//				}
//			}
//			else {
				file = new File(workingDir, varId);
				IOUtils.toFile(value.toValueJsonString(), StandardCharsets.UTF_8, file);
				
				return new FileVariable(varId, file);
//			}
		}
		catch ( IOException e ) {
			throw new InternalException("Failed to write value to file: name=" + varId
										+ ", path=" + file.getAbsolutePath(), e);
		}
	}
    
    private OperationSession removeOperationSessionInGuard(String opId) {
    	if ( s_logger.isDebugEnabled() ) {
    		s_logger.debug("removing OperationSession: id={}", opId);
    	}
    	
    	return m_sessions.remove(opId);
    }
	
	private void onClosedSessionExpired(RemovalNotification<String, OperationSession> noti) {
		if ( noti.wasEvicted() && noti.getCause() == RemovalCause.EXPIRED ) {
			s_logger.debug("expired closed-session: session={}", noti.getKey());
		}
	}
	
	private static class OperationSession implements AutoCloseable {
		private final String m_opId;
		private final OperationRequest m_request;
		private ProgramOperationConfiguration m_config;
		private volatile String m_sessionId;
		private CommandExecution m_cmdExec;
		
		private OperationSession(OperationRequest request) {
			m_opId = request.getOperation();
			m_request = request;
		}
		
		public static OperationSession create(OperationRequest request) {
			OperationSession session = new OperationSession(request);
			session.m_sessionId = Integer.toHexString(session.hashCode());
			
			return session;
		}
		
		public void close() {
			m_cmdExec.close();
		}
		
		public String getOperation() {
			return m_opId;
		}
		
		public String getSessionId() {
			return m_sessionId;
		}
		
		public ProgramOperationConfiguration getProgramOperationConfiguration() {
			return m_config;
		}
		
		public void setProgramOperationConfiguration(ProgramOperationConfiguration config) {
			m_config = config;
		}
		
		public CommandExecution getCommandExecution() {
			return m_cmdExec;
		}
		
		public void setCommandExecution(CommandExecution cmdExec) {
			m_cmdExec = cmdExec;
		}
		
		@Override
		public String toString() {
			return String.format("OperationSession{id=%s, op=%s, state=%s}",
										m_sessionId, m_opId, m_cmdExec.getState());
		}
	}
}


