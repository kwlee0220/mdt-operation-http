package mdt.operation.servers;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;

import utils.KeyValue;
import utils.Preconditions;
import utils.Throwables;
import utils.func.FOption;
import utils.func.Funcs;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.json.JacksonUtils;
import utils.rpc.restful.RESTfulAsyncRpcServer;
import utils.rpc.restful.RpcRequestMessage;
import utils.rpc.restful.RpcResponseMessage;
import utils.rpc.restful.process.RESTfulCommandExecutionServer;
import utils.stream.FStream;

import mdt.client.HttpMDTManager;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;


/**
 * MDT 연산(operation)을 RESTful RPC로 노출하는 Spring 디스패처 컨트롤러.
 * <p>
 * 기동 시 {@code operations} 디렉터리 하위의 각 연산 디렉터리에서 {@code operation.json}을 읽어
 * {@link RESTfulCommandExecutionServer}를 등록하고, 다음 엔드포인트로 연산의 시작·상태조회·취소를 중계한다.
 * <ul>
 *   <li>{@code POST   /api/v1/operations/{id}} — 연산 시작 ({@link #run})</li>
 *   <li>{@code GET    /api/v1/sessions/{id}/state} — 세션 상태 조회 ({@link #status})</li>
 *   <li>{@code DELETE /api/v1/sessions/{session}} — 세션 취소 ({@link #delete})</li>
 * </ul>
 * 시작된 세션은 세션 엔드포인트 → RPC 서버 매핑으로 캐시되며(최종 접근 후 30초 뒤 만료), 입력/출력 변수의
 * MDT 모델 직렬화는 {@link MDTCommandVariableSerDe}가 담당한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@RestController
@RequestMapping("/api/v1")
public class RpcServerDispatcher implements InitializingBean {
	private static final Logger s_logger = LoggerFactory.getLogger(RpcServerDispatcher.class);
	
	@Autowired private RpcServersConfiguration m_config;
	private MDTInstanceManager m_manager;
	private final Map<String,RpcServerInfo> m_serverInfos = new HashMap<>();
	private final JsonMapper m_jsonMapper = JacksonUtils.MAPPER;
	private MDTCommandVariableSerDe m_serde;

	private Cache<String,RpcServerInfo> m_sessions = CacheBuilder.newBuilder()
																.expireAfterAccess(Duration.ofSeconds(30))
																.removalListener(this::onClosedSessionExpired)
																.build();
	private void onClosedSessionExpired(RemovalNotification<String, RpcServerInfo> noti) {
		if ( noti.wasEvicted() && noti.getCause() == RemovalCause.EXPIRED ) {
			s_logger.debug("expired closed-session: session={}", noti.getKey());
		}
	}

	
	private static record RpcServerInfo(String opId, RESTfulAsyncRpcServer rpcServer) { }

	@Override
	public void afterPropertiesSet() throws Exception {
		Preconditions.checkState(m_config != null);

		String instanceManagerUrl = m_config.getInstanceManagerUrl();
		if ( instanceManagerUrl == null ) {
			throw new IllegalStateException("instanceManagerUrl is not configured");
		}
		HttpMDTManager mdt = HttpMDTManager.connect(instanceManagerUrl);
		m_manager = mdt.getInstanceManager();
		
		m_serde = new MDTCommandVariableSerDe(m_manager);

		File operationsDir = m_config.getOperationsDir();
		File[] opDirs = operationsDir.listFiles(File::isDirectory);
		if ( opDirs == null ) {
			// operations 디렉터리가 없거나 디렉터리가 아니면 등록 없이 기동한다.
			s_logger.warn("operations directory not found: {}", operationsDir.getAbsolutePath());
			return;
		}

		// operations 디렉터리 하위의 각 operation 디렉터리에서 operation.json 파일을 찾아서
		// RPC 서버를 등록한다.
		FStream.of(opDirs)
				.map(dir -> KeyValue.of(dir.getName(), FileUtils.path(dir, "operation.json")))
				.filter(kv -> kv.value().isFile())
				.forEach(kv -> {
					try {
						RESTfulAsyncRpcServer server = new RESTfulCommandExecutionServer(kv.value(), m_serde);
						RpcServerInfo info = new RpcServerInfo(kv.key(), server);
						m_serverInfos.put(kv.key(), info);
						s_logger.info("registered RPC operation: id={}, desc={}", kv.key(), kv.value());
					}
					catch ( IOException e ) {
						s_logger.warn("failed to load operation: id=" + kv.key(), e);
					}
				});

		s_logger.info("registered {} RPC operation(s) from {}",
						m_serverInfos.size(), operationsDir.getAbsolutePath());
	}

    /**
     * 지정한 연산을 시작시킨다. ({@code POST /api/v1/operations/{id}})
     * <p>
     * 등록되지 않은 연산이면 {@code operations} 디렉터리에서 지연 로딩을 시도하고, 그래도 없으면 404를 반환한다.
     * 시작에 성공하여 세션이 생성되면 세션 맵에 등록하고 {@code 202 Accepted}로 응답 메시지를 반환한다.
     *
     * @param opId			연산 식별자.
     * @param requestJson	{@link RpcRequestMessage} 형식의 요청 본문(JSON).
     * @return	연산 시작 결과를 담은 응답({@code 202}) 또는 연산 미존재 시 {@code 404}.
     * @throws IOException	요청 파싱 또는 연산 로딩 중 입출력 오류가 발생한 경우.
     */
    @PostMapping("/operations/{id}")
    public ResponseEntity<?> run(@PathVariable("id") String opId, @RequestBody String requestJson)
    																				throws IOException {
    	Preconditions.checkNotNullArgument(opId, "operation id is null");
    	
    	// 요청 JSON을 RpcRequestMessage로 변환한다.
    	RpcRequestMessage request = m_jsonMapper.readValue(requestJson, RpcRequestMessage.class);
    	
    	RpcServerInfo info = m_serverInfos.get(opId);
    	if ( info == null ) {
    		info = loadRpcServer(opId);
    	}
    	if ( info == null ) {
    		ResourceNotFoundException cause = new ResourceNotFoundException("RpcServer", "id=" + opId);
    		return ResponseEntity.status(HttpStatus.NOT_FOUND)
								.body(RESTfulErrorEntity.of(cause));
    	}
    	
    	// RPC 요청 메시지를 해당 RPC 서버에 전달하여 연산을 수행하고, 응답 메시지를 받는다.
    	RpcResponseMessage resp = info.rpcServer.start(request);
    	if ( resp.getSessionEndpoint() != null ) {
    		// 세션이 생성되었으면, 세션 맵에 등록한다.
    		m_sessions.put(resp.getSessionEndpoint(), info);
    	}
    	
    	return ResponseEntity.accepted().body(resp);
    }
    
    /**
     * 세션의 현재 연산 상태를 조회한다. ({@code GET /api/v1/sessions/{id}/state})
     * <p>
     * 세션을 찾지 못하거나 이미 종료되어 회수된 경우 {@code 404}를 반환하고, 그 경우 세션 맵에서도 제거한다.
     *
     * @param sessionId	세션 식별자.
     * @return	연산 상태를 담은 응답({@code 200}) 또는 세션 미존재 시 {@code 404}.
     * @throws IOException	상태 조회 중 입출력 오류가 발생한 경우.
     */
    @GetMapping("/sessions/{id}/state")
    public ResponseEntity<?> status(@PathVariable("id") String sessionId) throws IOException {
    	Preconditions.checkNotNullArgument(sessionId, "session id is null");

    	// 세션 식별자를 이용하여 세션 맵에서 해당 세션을 수행 중인 RPC 서버를 찾는다.
    	String sessionEndpoint = String.format("/sessions/%s", sessionId);
    	RpcServerInfo rpcInfo = findRpcServer(sessionEndpoint);
    	if ( rpcInfo == null ) {
    		var cause = new ResourceNotFoundException("RpcSession", "session=" + sessionEndpoint);
    		return ResponseEntity.status(HttpStatus.NOT_FOUND)
    							.body(RESTfulErrorEntity.of(cause));
    	}
    	RpcResponseMessage resp = rpcInfo.rpcServer.status(sessionEndpoint);
    	if ( resp == null ) {
			// 세션이 이미 종료되었으면, 세션 맵에서 제거한다.
    		m_sessions.invalidate(sessionEndpoint);
    		
    		String resourceId = String.format("rpc=%s, session=%s", rpcInfo.opId, sessionEndpoint);
			var cause = new ResourceNotFoundException("RpcSession", resourceId);
    		return ResponseEntity.status(HttpStatus.NOT_FOUND)
								.body(RESTfulErrorEntity.of(cause));
		}
    	return ResponseEntity.ok().body(resp);
    }

    /**
     * 세션의 연산을 취소한다. ({@code DELETE /api/v1/sessions/{session}})
     * <p>
     * 취소에 성공하거나 이미 종료된 경우 {@code 204 No Content}를, 취소 처리 중 오류가 보고되면
     * {@code 500}과 오류 정보를 반환한다.
     *
     * @param sessionId	세션 식별자.
     * @return	취소 결과 응답({@code 204} 또는 오류 시 {@code 500}).
     */
    @DeleteMapping("/sessions/{session}")
    public ResponseEntity<?> delete(@PathVariable("session") String sessionId) {
    	Preconditions.checkNotNullArgument(sessionId, "session id is null");

    	// 세션 식별자를 이용하여 세션 맵에서 해당 세션을 수행 중인 RPC 서버를 찾는다.
    	String sessionEndpoint = String.format("/sessions/%s", sessionId);
    	RpcServerInfo rpcInfo = findRpcServer(sessionEndpoint);
    	if ( rpcInfo == null ) {
    		throw new ResourceNotFoundException("RpcSession", "session=" + sessionEndpoint);
    	}
    	
    	RpcResponseMessage resp = rpcInfo.rpcServer.cancel(sessionEndpoint);
    	if ( resp == null ) {
			// 세션이 이미 종료되었으면, 세션 맵에서 제거한다.
    		m_sessions.invalidate(sessionEndpoint);
			
    		return ResponseEntity.noContent().build();
		}
    	if ( resp.getError() == null ) {
    		return ResponseEntity.noContent().build();
    	}
    	else {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    								.body(resp.getError());
    	}
    }
    
    @ExceptionHandler()
    public ResponseEntity<RESTfulErrorEntity> handleException(Exception e) {
		Throwable cause = Throwables.unwrapThrowable(e);
		s_logger.error("Exception raised", cause);
    	if ( cause instanceof IllegalArgumentException ) {
    		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof TimeoutException ) {
    		return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    	else if ( cause instanceof ResourceNotFoundException ) {
    		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RESTfulErrorEntity.of(cause));
    	}
    	else {
    		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    								.body(RESTfulErrorEntity.of(cause));
    	}
    }
    
    /**
     * 세션 엔드포인트를 수행 중인 RPC 서버를 찾는다.
     * <p>
     * 세션 캐시를 먼저 조회하고, 없으면 등록된 모든 RPC 서버를 훑어 해당 세션을 가진 서버를 찾아 캐시에 채운다.
     *
     * @param sessionEndpoint	세션 엔드포인트(예: {@code /sessions/{id}}).
     * @return	해당 세션을 수행 중인 RPC 서버 정보. 없으면 {@code null}.
     */
    private RpcServerInfo findRpcServer(String sessionEndpoint) {
    	// 세션 캐쉬에서 세션 엔드포인트에 해당하는 RPC 서버를 찾는다.
    	RpcServerInfo rpcInfo = m_sessions.getIfPresent(sessionEndpoint);
    	if ( rpcInfo != null ) {
    		return rpcInfo;
    	}
    	
    	// 세션 캐쉬에 없으면, 등록된 RPC 서버들 중에서 세션 엔드포인트를 수행 중인 서버를 찾는다.
    	rpcInfo = Funcs.findFirst(m_serverInfos.values(),
								info -> info.rpcServer.getSession(sessionEndpoint) != null);
    	if ( rpcInfo != null ) {
    		m_sessions.put(sessionEndpoint, rpcInfo);
		}
    	
    	return rpcInfo;
	}
	
	/**
	 * 아직 등록되지 않은 연산을 {@code operations} 디렉터리에서 지연 로딩하여 등록한다.
	 *
	 * @param rpcId	로딩할 연산 식별자.
	 * @return	등록된 RPC 서버 정보. 해당 연산 디렉터리/디스크립터가 없으면 {@code null}.
	 * @throws IOException	{@code operations} 디렉터리가 없거나 디스크립터 로딩 중 오류가 발생한 경우.
	 */
	private RpcServerInfo loadRpcServer(String rpcId) throws IOException {
		// operations 디렉터리 하위의 각 operation 디렉터리 중에서 operation.json 파일을 포함한
		// 모든 RPC 디렉토리에서 이미 등록되지 않은 디렉토리를 찾아서 RPC 서버를 등록한다.
		File operationsDir = m_config.getOperationsDir();
		File[] opDirs = operationsDir.listFiles(File::isDirectory);
		if ( opDirs == null ) {
			// operations 디렉터리가 없거나 디렉터리가 아니면 등록 없이 기동한다.
			throw new IOException("operations directory not found: " + operationsDir.getAbsolutePath());
		}
		
		FOption<File> opDescFile = FStream.of(opDirs)
										.filter(dir -> dir.getName().equals(rpcId) && !m_serverInfos.containsKey(dir.getName()))
										.map(dir -> FileUtils.path(dir, "operation.json"))
										.filter(f -> f.isFile())
										.findFirst();
		return opDescFile.mapOrThrow(dfile -> {
								RESTfulAsyncRpcServer server = new RESTfulCommandExecutionServer(dfile, m_serde);
								RpcServerInfo info = new RpcServerInfo(rpcId, server);
								m_serverInfos.put(rpcId, info);
								
								return info;
							})
						.getOrNull();
	}
}


