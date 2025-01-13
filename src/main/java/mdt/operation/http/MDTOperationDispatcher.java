package mdt.operation.http;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
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
import com.google.common.collect.Maps;

import utils.InternalException;
import utils.Throwables;
import utils.async.Guard;
import utils.func.Either;
import utils.func.FOption;
import utils.func.Funcs;
import utils.func.Tuple;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.stream.FStream;

import mdt.client.operation.OperationRequestBody;
import mdt.client.operation.OperationResponse;
import mdt.model.AASUtils;
import mdt.model.MDTManager;
import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;
import mdt.task.Parameter;
import mdt.task.builtin.ProgramOperationDescriptor;
import mdt.task.builtin.ProgramTask;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping(value={""})
public class MDTOperationDispatcher implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(MDTOperationDispatcher.class);
	private static final Duration SESSION_RETAIN_TIMEOUT = Duration.ofMinutes(5);
	
	@Autowired private MDTManager m_mdt;
	private MDTInstanceManager m_manager;
	@Autowired private OperationServerConfiguration m_config;
	private File m_homeDir;

	private final Guard m_guard = Guard.create();
	@GuardedBy("m_guard") private final Map<String,OperationSession> m_sessions = Maps.newHashMap();
	@GuardedBy("m_guard") private Cache<String,OperationSession> m_closedSessions
															= CacheBuilder.newBuilder()
																			.expireAfterWrite(SESSION_RETAIN_TIMEOUT)
																			.build();
	
	@Override
	public void afterPropertiesSet() throws Exception {
		Preconditions.checkState(m_mdt != null);
		m_manager = m_mdt.getInstanceManager();
		
		// 설정 파일에 'homeDir'이 지정되지 않은 경우에는 'operations' 파일이 위치한
		// 디렉토리를 사용한다.
		m_homeDir = FOption.getOrElse(m_config.getHomeDir(), FileUtils::getCurrentWorkingDirectory);
	}

    @PostMapping("/operations/{opId}/sync")
    public ResponseEntity<?> runSync(@PathVariable("opId") String encodedOpId,
    									@RequestBody String reqBodyJson)
    	throws TimeoutException, CancellationException, InterruptedException, ExecutionException {
    	String opId = AASUtils.decodeBase64UrlSafe(encodedOpId);
    	OperationRequestBody reqBody;
		try {
			reqBody = OperationRequestBody.parseJsonString(reqBodyJson);
		}
		catch ( IOException e ) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
								.body(RESTfulErrorEntity.of("Bad request body: " + reqBodyJson, e));
		}
    	
    	Either<OperationSession, ResponseEntity<RESTfulErrorEntity>> result = start(opId, reqBody);
    	if ( result.isRight() ) {
    		return result.right().get();
    	}
    	
    	OperationSession opSession = result.left().get();
    	ProgramOperationDescriptor opDesc = opSession.m_task.getOperationDescriptor();
		try {
			Duration timeout = opDesc.getTimeout();
			if ( timeout != null ) {
				opSession.m_task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			else {
				opSession.m_task.get();
			}
    		return buildResponse(opSession);
		}
		finally {
			m_sessions.remove(opSession.m_sessionId);
		}
    }

    @PostMapping("/operations/{opId}/async")
    public ResponseEntity<?> runAsync(@PathVariable("opId") String encodedOpId,
    									@RequestBody String reqBodyJson)
    	throws TimeoutException, CancellationException, InterruptedException, ExecutionException {
    	String opId = AASUtils.decodeBase64UrlSafe(encodedOpId);
    	OperationRequestBody reqBody;
		try {
			reqBody = OperationRequestBody.parseJsonString(reqBodyJson);
		}
		catch ( IOException e ) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
								.body(RESTfulErrorEntity.of("Bad request body: " + reqBodyJson, e));
		}
    	
    	Either<OperationSession, ResponseEntity<RESTfulErrorEntity>> result = start(opId, reqBody);
    	if ( result.isRight() ) {
    		return result.right().get();
    	}

    	OperationSession opSession = result.left().get();
		OperationResponse resp = OperationResponse.running(opSession.m_sessionId, "Operation is running");
		try {
			return ResponseEntity.created(new URI("")).body(resp);
		}
		catch ( URISyntaxException e ) {
			throw new InternalException("invalid 'Location': " + opSession.m_opId);
		}
    }

    @GetMapping("/sessions/{session}")
    public ResponseEntity<?> status(@PathVariable("session") String sessionId) {
    	sessionId = AASUtils.decodeBase64UrlSafe(sessionId);
    	
    	m_guard.lock();
    	try {
    		OperationSession session = m_sessions.get(sessionId);
    		
    		// Operation id에 해당하는 실행 등록 정보가 없는 경우에는 NOT_FOUND 오류를 발생시킨다.
    		if ( session == null ) {
    			OperationSession closedSession = m_closedSessions.getIfPresent(sessionId);
    			if ( closedSession != null ) {
    	    		return buildResponse(closedSession);
    			}
    			else {
	    			String msg = "Operation is not found: opId=" + sessionId;
	    			return ResponseEntity.status(HttpStatus.NOT_FOUND)
	    								.body(RESTfulErrorEntity.ofMessage(msg));
    			}
    		}
    		
    		if ( session.m_task.isFinished() ) {
    			removeOperationSessionInGuard(sessionId);
        		m_guard.signalAll();
    		}
    		
    		return buildResponse(session);
    	}
    	finally {
    		m_guard.unlock();
    	}
    }

    @DeleteMapping("/sessions/{session}")
    public ResponseEntity<Void> delete(@PathVariable("session") String sessionId) {
    	sessionId = AASUtils.decodeBase64UrlSafe(sessionId);
    	
    	m_guard.lock();
    	try {
    		OperationSession opExec = m_sessions.remove(sessionId);
    		if ( opExec != null ) {
        		opExec.m_task.cancel(true);
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
    	if ( e instanceof ExecutionException ) {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    								.body(RESTfulErrorEntity.of(e));
    	}
		Throwable cause = Throwables.unwrapThrowable(e);
    	if ( cause instanceof IllegalArgumentException ) {
    		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof TimeoutException ) {
    		return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    }

    private Either<OperationSession, ResponseEntity<RESTfulErrorEntity>> start(String opId,
    																			OperationRequestBody reqBody) {
    	File opHome = new File(m_homeDir, opId);
    	if ( !opHome.isDirectory() ) {
    		ResourceNotFoundException ex = new ResourceNotFoundException("Operation", "opId=" + opId);
			return Either.right(ResponseEntity.badRequest().body(RESTfulErrorEntity.of(ex)));
    	}

    	OperationSession session;
    	try {
        	File opDescFile = new File(opHome, "operation.json");
    		ProgramOperationDescriptor opDesc = ProgramOperationDescriptor.load(opDescFile,
    																			MDTModelSerDe.getJsonMapper());
    		
    		// working directory가 별도로 설정되어 있지 않으면
    		// 기본 directory로 설정한다.
    		FOption.ifAbsent(opDesc.getWorkingDirectory(), () -> opDesc.setWorkingDirectory(opHome));
    		
    		ProgramTask task = new ProgramTask(opDesc);
    		task.setMDTInstanceManager(m_manager);
    		
    		// OperationRequestBody의 파라미터들을 outputNames에 포함 여부에 따라
    		// input parameter와 output parameter로 분리하고, 각각을 Task에 추가한다.
    		Tuple<List<Parameter>,List<Parameter>> parts
    				= Funcs.partition(reqBody.getParameters(), p -> reqBody.getOutputNames().contains(p.getName()));
    		parts._2.forEach(task::addOrReplaceInputParameter);
    		parts._1.forEach(task::addOrReplaceOutputParameter);
    		
    		session = OperationSession.create(opId, task);
    	}
    	catch ( IOException e ) {
    		String msg = String.format("Failed to create CommandExecution: cause=%s", e);
    		s_logger.error(msg);
    		
    		return Either.right(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    											.body(RESTfulErrorEntity.ofMessage(msg)));
    	}
    	
    	m_guard.lock();
    	try {
    		// 동시 연산 수행을 지원하지 않는 경우에는 동일 연산이 수행 중인 경우에 예외를 발생시킨다.
    		if ( !session.m_task.getOperationDescriptor().isConcurrent() ) {
    			boolean existsOp = FStream.from(m_sessions)
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
    		session.m_task.whenFinished(result -> {
    			OperationSession closed = m_sessions.remove(session.m_sessionId);
    			if ( closed != null ) {
    				m_closedSessions.put(session.m_sessionId, closed);
    			}
    		});
    		
    		m_guard.signalAll();
    	}
    	finally {
    		m_guard.unlock();
    	}

    	session.m_task.start();
		return Either.left(session);
    }
    
    private ResponseEntity<OperationResponse> buildResponse(OperationSession session) {
    	String sessId = session.m_sessionId;
    	ProgramTask task = session.m_task;
    	switch ( task.getState() ) {
    		case RUNNING:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.running(sessId, "Operation is running"));
    		case COMPLETED:
        		return ResponseEntity.status(HttpStatus.OK)
    								.body(OperationResponse.completed(sessId, task.getOutputValues()));
    		case FAILED:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.failed(sessId, task.getResult().getCause()));
    		case CANCELLED:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.cancelled(sessId, "Operation is cancelled"));
    		case CANCELLING:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.cancelled(sessId, "Operation is cancelling"));
    		case STARTING:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.cancelled(sessId, "Operation is starting"));
    		case NOT_STARTED:
    			return ResponseEntity.status(HttpStatus.OK)
									.body(OperationResponse.cancelled(sessId, "Operation is not found"));
    		default:
    			String msg = "Unexpected execution status: " + task.getState();
    			throw new InternalException(msg);
    	}
    }
    
    private OperationSession removeOperationSessionInGuard(String opId) {
    	if ( s_logger.isDebugEnabled() ) {
    		s_logger.debug("removing OperationSession: id={}", opId);
    	}
    	
    	return m_sessions.remove(opId);
    }
	
	private static class OperationSession {
		private final String m_opId;
		private volatile String m_sessionId;
		private final ProgramTask m_task;
		
		public static OperationSession create(String opId, ProgramTask task) {
			OperationSession session = new OperationSession(opId, task);
			session.m_sessionId = Integer.toHexString(session.hashCode());
			
			return session;
		}
		
		private OperationSession(String opId, ProgramTask task) {
			m_opId = opId;
			m_task = task;
		}
	}

//	private ProgramOperationConfiguration loadProgramOperationConfiguration(File rootDir, JsonNode confNode)
//		throws IOException {
//		String confStr = MAPPER.writeValueAsString(confNode);
//		
//		// Windows OS의 경우 환경 변수 내 파일 경로명에 포함된 file separator가 '\'이기 때문에
//		// 이 값을 이용하여 string substitution을 사용하여 JSON 파일 구성하면
//		// 이 '\'가 escape character로 간주되어 문제를 유발한다.
//		// 이를 해결하기 위해 '\'를 '/'로 대체시킨다.
//		Map<String,String> vars = FStream.from(System.getenv())
//										.mapValue(v -> v.replaceAll("\\\\", "/"))
//										.toMap();
//		vars.put("MDT_OPERATION_SERVER_DIR", rootDir.getAbsolutePath().replaceAll("\\\\", "/"));
//		
//		// 일단 MDT_OPERATION_SERVER_DIR 만 포함시킨채로 variable-replacement를 실시한 
//		// 상태에서 설정 정보를 파싱한다.
//		confStr = Utilities.substributeString(confStr, vars);
//		ProgramOperationConfiguration poConf = MAPPER.readValue(confStr, ProgramOperationConfiguration.class);
//
//		
//		// MDT_OPERATION_DIR를 설정한 상태에서 variable-replacement를 실시한 설정 정보를 다시 파싱한다.
//		String opWorkDir = FOption.getOrElse(poConf.getWorkingDirectory(),
//												new File(rootDir, poConf.getId()).getAbsolutePath());
//		opWorkDir = opWorkDir.replaceAll("\\\\", "/");
//		vars.put("MDT_OPERATION_DIR", opWorkDir);
//		opWorkDir = Utilities.substributeString(opWorkDir, vars);
//		
//		if ( !new File(opWorkDir).isDirectory() ) {
//			s_logger.error("  failed to program operation: id={}: invalid directory {} -> ignored",
//							poConf.getId(), opWorkDir);
//			return null;
//		}
//		
//		poConf.setWorkingDirectory(opWorkDir);
//		
//		confStr = Utilities.substributeString(confStr, vars);
//		poConf = MAPPER.readValue(confStr, ProgramOperationConfiguration.class);
//		if ( poConf.getWorkingDirectory() == null ) {
//			poConf.setWorkingDirectory(opWorkDir);
//		}
//		
//		if ( s_logger.isInfoEnabled() ) {
//			s_logger.info("  loading program operation: id={}, dir={}", poConf.getId(),
//							poConf.getWorkingDirectory());
//		}
//		
//		return poConf;
//	}
}


