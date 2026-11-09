package mdt.operation.server;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import utils.Preconditions;
import utils.Throwables;
import utils.func.Funcs;
import utils.http.RESTfulErrorEntity;
import utils.io.FileUtils;
import utils.rpc.restful.RESTfulAsyncRpcServer;
import utils.rpc.restful.RpcRequestMessage;
import utils.rpc.restful.RpcResponseMessage;
import utils.rpc.restful.RpcState;
import utils.rpc.restful.process.CommandExecutionDescriptor;
import utils.rpc.restful.process.RESTfulCommandExecutionServer;
import utils.stream.FStream;

import mdt.client.HttpMDTManager;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;


/**
 * MDT 연산(operation)을 RESTful RPC로 노출하는 Spring 컨트롤러.
 * <p>
 * 2026-11-08 이전 이름은 {@code mdt.operation.servers.RpcServerDispatcher}였다(검토 N1·N2).
 * 같은 날 엔드포인트 메서드 {@code run}/{@code status}/{@code delete}를 {@link #startOperation}/
 * {@link #getSessionStatus}/{@link #cancelSession}으로, 경로 변수 {@code {id}}/{@code {sid}}를
 * {@code {opId}}/{@code {sessionId}}로 바꿨다. URL은 그대로다(검토 N5·N6).
 * <p>
 * 기동 시 {@code operations} 디렉터리 하위의 각 연산 디렉터리에서 {@code operation.json}을 읽어
 * {@link RESTfulCommandExecutionServer}를 등록하고, 다음 엔드포인트를 제공한다.
 * <ul>
 *   <li>{@code GET    /api/v1/operations} — 연산 목록 ({@link #listOperations})</li>
 *   <li>{@code GET    /api/v1/operations/{opId}} — 연산 정보 ({@link #getOperation})</li>
 *   <li>{@code POST   /api/v1/operations/{opId}} — 연산 시작 ({@link #startOperation})</li>
 *   <li>{@code POST   /api/v1/operations/{opId}/reload} — 연산 디스크립터 다시 읽기 ({@link #reload})</li>
 *   <li>{@code GET    /api/v1/sessions/{sessionId}} — 세션 상태 조회 ({@link #getSessionStatus})</li>
 *   <li>{@code DELETE /api/v1/sessions/{sessionId}} — 세션 취소 ({@link #cancelSession})</li>
 * </ul>
 * 세션을 가진 RPC 서버는 조회할 때마다 등록된 서버들의 {@link RESTfulAsyncRpcServer#getSession}으로 찾는다.
 * 입력/출력 변수의 MDT 모델 직렬화는 {@link MDTCommandVariableSerDe}가 담당한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Tag(name = "MDT 연산 API")
@RestController
@RequestMapping(OperationController.API_PREFIX)
public class OperationController implements InitializingBean, DisposableBean {
	private static final Logger s_logger = LoggerFactory.getLogger(OperationController.class);

	/** 이 컨트롤러가 노출하는 모든 엔드포인트의 접두사. 세션 엔드포인트 문자열도 이 접두사를 포함한다. */
	static final String API_PREFIX = "/api/v1";

	/** {@link #reload}로 교체된 서버를, 세션이 없어도 이 시간 동안은 세션 조회 대상으로 남긴다. */
	private static final Duration RETIRED_SERVER_GRACE = Duration.ofMinutes(10);
	/** 서버를 종료할 때 실행 중인 연산이 취소되어 끝나기를 기다리는 최대 시간. */
	private static final Duration SHUTDOWN_CANCEL_TIMEOUT = Duration.ofSeconds(10);
	/** {@link RESTfulCommandExecutionServer}가 만드는 세션 디렉터리 이름({@code session-<UUID>-<seq>}). */
	private static final Pattern SESSION_DIR_NAME
			= Pattern.compile("session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-\\d+");

	@Autowired private OperationServerProperties m_config;
	private MDTInstanceManager m_manager;
	// 요청 스레드가 처음 보는 연산을 지연 등록(findOrLoadOperation)하므로 동시 접근에 안전한 맵을 쓴다.
	// 2026-11-08 이전에는 HashMap이어서 동시 요청 시 맵이 깨지거나 같은 연산의 서버가 두 번 만들어질 수 있었다(검토 D1).
	private final Map<String,RegisteredOperation> m_operations = new ConcurrentHashMap<>();
	// reload로 교체된 서버. 교체 전에 시작된 세션을 계속 조회·취소할 수 있도록 남겨 둔다(검토 I4).
	private final List<RetiredOperation> m_retiredOperations = new CopyOnWriteArrayList<>();
	private MDTCommandVariableSerDe m_serde;

	private static record RegisteredOperation(String opId, RESTfulCommandExecutionServer server) { }
	private static record RetiredOperation(RegisteredOperation operation, Instant retiredAt) { }

	/**
	 * 연산 목록·정보 조회 API의 응답 항목.
	 * <p>
	 * 명령줄과 작업 디렉터리는 서버 내부 경로가 드러나므로 포함하지 않는다. 시간 값은 ISO-8601 기간 문자열
	 * (예: {@code PT1M})이며, 디스크립터에 지정되지 않았으면 {@code null}이다.
	 *
	 * @param id					연산 식별자.
	 * @param outputVariables		출력 변수 이름 목록.
	 * @param timeout				명령 실행 제한 시간.
	 * @param startTimeout			명령 시작 대기 제한 시간.
	 * @param sessionRetainTimeout	종료된 세션의 결과 보관 시간.
	 */
	public static record OperationInfo(String id, List<String> outputVariables, String timeout,
										String startTimeout, String sessionRetainTimeout) {
		static OperationInfo of(String id, CommandExecutionDescriptor desc) {
			return new OperationInfo(id, desc.getOutputVariables(), toString(desc.getTimeout()),
									toString(desc.getStartTimeout()), toString(desc.getSessionRetainTimeout()));
		}

		private static String toString(Duration dur) {
			return (dur != null) ? dur.toString() : null;
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		Preconditions.checkState(m_config != null);

		// mdtManagerUrl이 없는 설정은 OperationServerProperties가 바인딩 단계에서 거부한다(검토 I8).
		HttpMDTManager mdt = HttpMDTManager.connect(m_config.getMdtManagerUrl());
		m_manager = mdt.getInstanceManager();

		m_serde = new MDTCommandVariableSerDe(m_manager);

		File operationsDir = m_config.getOperationsDir();
		if ( !operationsDir.isDirectory() ) {
			// operations 디렉터리가 없거나 디렉터리가 아니면 등록 없이 기동한다.
			s_logger.warn("operations directory not found: {}", operationsDir.getAbsolutePath());
			return;
		}

		// operations 디렉터리 하위의 각 operation 디렉터리에서 operation.json 파일을 찾아서
		// RPC 서버를 등록한다. 디스크립터가 잘못된 연산은 경고만 남기고 건너뛴다.
		loadAllOperations();
		s_logger.info("registered {} RPC operation(s) from {}",
						m_operations.size(), operationsDir.getAbsolutePath());
		
		removeStaleSessionDirectories();
	}
	
	/**
	 * 서버가 종료될 때 실행 중인 모든 연산을 취소한다.
	 * <p>
	 * 취소된 명령의 외부 프로세스(하위 프로세스 포함)는 종료되고 세션 디렉터리는 지워진다.
	 * {@link #SHUTDOWN_CANCEL_TIMEOUT} 안에 끝나지 않은 연산은 기다리지 않는다. 2026-11-08 이전에는 종료 처리가
	 * 없어, 실행 중이던 외부 프로세스가 고아로 남고 세션 디렉터리도 남았다(검토 I7).
	 */
	@Override
	public void destroy() {
		List<RegisteredOperation> ops = FStream.from(m_operations.values())
											.concatWith(FStream.from(m_retiredOperations).map(RetiredOperation::operation))
											.toList();
		s_logger.info("cancelling running RPC sessions: operations={}", ops.size());
		for ( RegisteredOperation op: ops ) {
			op.server.cancelAllSessions(SHUTDOWN_CANCEL_TIMEOUT);
		}
	}

	/**
	 * 연산 목록을 반환한다. ({@code GET /api/v1/operations})
	 * <p>
	 * 서버가 떠 있는 동안 추가된 연산 디렉터리도 이때 읽어 등록한다. 디스크립터를 읽지 못한 연산은 경고만
	 * 남기고 목록에서 뺀다. 목록은 연산 식별자 순으로 정렬한다.
	 *
	 * @return	연산 정보 목록.
	 */
	@Operation(summary = "등록된 연산 목록을 반환한다.")
	@GetMapping("/operations")
	public List<OperationInfo> listOperations() {
		loadAllOperations();
		return FStream.from(m_operations.values())
						.map(OperationController::toOperationInfo)
						.sort(Comparator.comparing(OperationInfo::id))
						.toList();
	}

	/**
	 * 연산 정보를 반환한다. ({@code GET /api/v1/operations/{opId}})
	 *
	 * @param opId	연산 식별자.
	 * @return	연산 정보.
	 * @throws ResourceNotFoundException	연산이 없는 경우 ({@code 404}).
	 * @throws IOException	연산 디스크립터 로딩 중 입출력 오류가 발생한 경우.
	 */
	@Operation(summary = "연산 정보(출력 변수, 제한 시간)를 반환한다.")
	@GetMapping("/operations/{opId}")
	public OperationInfo getOperation(@PathVariable("opId") String opId) throws IOException {
		return toOperationInfo(getOrLoadOperation(opId));
	}

    /**
     * 지정한 연산을 시작시킨다. ({@code POST /api/v1/operations/{opId}})
     * <p>
     * 등록되지 않은 연산이면 {@code operations} 디렉터리에서 지연 로딩을 시도하고, 그래도 없으면 404를 반환한다.
     * 시작에 성공하여 세션이 생성되면 {@code 202 Accepted}로 응답 메시지를 반환한다.
     * <p>
     * 입력 값이 잘못되는 등 연산을 시작하지 못한 경우에도 {@code 202}와 함께 {@code FAILED} 상태의 응답 메시지를
     * 반환한다. 호출 측 클라이언트({@code RESTfulAsyncRpcClient}, mdtpy)는 2xx 응답의 {@code FAILED}만 연산
     * 실패로 해석하기 때문이다(검토 D3).
     * <p>
     * 요청 본문은 Spring이 {@link RpcRequestMessage}로 변환한다. 본문이 JSON이 아니거나 형식이 맞지 않으면
     * {@link HttpMessageNotReadableException}이 나고 {@code 400}으로 응답한다(검토 I1).
     *
     * @param opId		연산 식별자.
     * @param request	연산 시작 요청 메시지.
     * @return	연산 시작 결과를 담은 응답({@code 202}).
     * @throws ResourceNotFoundException	연산이 없는 경우 ({@code 404}).
     * @throws IllegalArgumentException	연산 식별자가 잘못된 경우 ({@code 400}).
     * @throws IOException	연산 디스크립터 로딩 중 입출력 오류가 발생한 경우.
     */
	@Operation(summary = "연산을 시작한다.")
    @PostMapping("/operations/{opId}")
    public ResponseEntity<RpcResponseMessage> startOperation(@PathVariable("opId") String opId,
    												@RequestBody RpcRequestMessage request) throws IOException {
    	RegisteredOperation op = getOrLoadOperation(opId);

    	// RPC 요청 메시지를 해당 RPC 서버에 전달하여 연산을 수행하고, 응답 메시지를 받는다.
    	// 생성된 세션은 이후 조회 시 findOperationBySession가 등록된 서버들에서 찾는다.
    	RpcResponseMessage resp = op.server.start(request);
    	return ResponseEntity.accepted().body(resp);
    }

	/**
	 * 연산 디스크립터({@code operation.json})를 다시 읽는다. ({@code POST /api/v1/operations/{opId}/reload})
	 * <p>
	 * 새 디스크립터로 RPC 서버를 새로 만들어 이후 요청부터 쓴다. 교체 전에 시작된 세션은 이전 서버에 남아
	 * 계속 조회·취소할 수 있다. 디스크립터를 읽지 못하면 이전 서버를 그대로 쓴다. 연산 디렉터리나 디스크립터가
	 * 없어졌으면 연산 등록을 해제하고 {@code 404}를 반환한다.
	 *
	 * @param opId	연산 식별자.
	 * @return	다시 읽은 연산 정보.
	 * @throws ResourceNotFoundException	연산 디스크립터가 없는 경우 ({@code 404}).
	 * @throws IllegalArgumentException	연산 식별자가 잘못된 경우 ({@code 400}).
	 * @throws IOException	디스크립터를 읽는 중 오류가 발생한 경우. 이전 서버를 그대로 쓴다.
	 */
	@Operation(summary = "연산 디스크립터를 다시 읽는다. 실행 중인 세션은 이전 설정으로 계속 수행된다.")
	@PostMapping("/operations/{opId}/reload")
	public OperationInfo reload(@PathVariable("opId") String opId) throws IOException {
		File descFile = getDescriptorFile(opId);
		pruneRetiredOperations();

		if ( !descFile.isFile() ) {
			retire(m_operations.remove(opId));
			throw new ResourceNotFoundException("Operation", "id=" + opId);
		}

		RegisteredOperation op = new RegisteredOperation(opId, new RESTfulCommandExecutionServer(descFile, m_serde));
		retire(m_operations.put(opId, op));
		s_logger.info("reloaded RPC operation: id={}, desc={}", opId, descFile);

		return toOperationInfo(op);
	}

    /**
     * 세션의 현재 연산 상태를 조회한다. ({@code GET /api/v1/sessions/{sessionId}})
     * <p>
     * 종료된 세션의 결과는 한 번만 돌려준다(read-once). 이미 회수되었거나 보존 기간이 지난 세션은 {@code 404}다.
     *
     * @param sessionId	세션 식별자.
     * @return	연산 상태를 담은 응답({@code 200}).
     * @throws ResourceNotFoundException	세션이 없는 경우 ({@code 404}).
     */
	@Operation(summary = "세션의 연산 상태를 조회한다. 종료된 세션의 결과는 한 번만 돌려준다.")
    @GetMapping("/sessions/{sessionId}")
    public RpcResponseMessage getSessionStatus(@PathVariable("sessionId") String sessionId) {
    	String sessionEndpoint = toSessionEndpoint(sessionId);
    	RegisteredOperation op = findOperationBySession(sessionEndpoint);

    	// 찾은 뒤 조회하기 전에 세션이 회수될 수 있으므로 status()의 null도 확인한다.
    	RpcResponseMessage resp = (op != null) ? op.server.status(sessionEndpoint) : null;
    	if ( resp == null ) {
    		throw new ResourceNotFoundException("RpcSession", "session=" + sessionEndpoint);
		}
    	return resp;
    }

    /**
     * 세션의 연산을 취소한다. ({@code DELETE /api/v1/sessions/{sessionId}})
     * <p>
     * 취소가 확정되면 {@code 200 OK}와 함께 {@code CANCELLED} 상태를 담은 {@link RpcResponseMessage}를
     * 반환한다. 대상 세션이 이미 종료 상태({@code COMPLETED}/{@code FAILED})이거나 연산이 취소 요청을
     * 거부하여 계속 수행 중({@code RUNNING})인 경우처럼 현재 상태에서 취소가 허용되지 않으면
     * {@code 409 Conflict}를 반환한다. 계속 수행 중인 세션은 남아 있어 이후에도 조회할 수 있다.
     *
     * @param sessionId	세션 식별자.
     * @return	취소 확정 시 {@code 200}, 취소가 허용되지 않으면 {@code 409}.
     * @throws ResourceNotFoundException	세션이 없는 경우 ({@code 404}).
     */
	@Operation(summary = "세션의 연산을 취소한다.")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> cancelSession(@PathVariable("sessionId") String sessionId) {
    	String sessionEndpoint = toSessionEndpoint(sessionId);
    	RegisteredOperation op = findOperationBySession(sessionEndpoint);

    	RpcResponseMessage resp = (op != null) ? op.server.cancel(sessionEndpoint) : null;
    	if ( resp == null ) {
    		throw new ResourceNotFoundException("RpcSession", "session=" + sessionEndpoint);
		}

    	if ( resp.getState() != RpcState.CANCELLED ) {
    		// 현재 상태에서 취소가 허용되지 않는 경우이다.
    		String details = String.format("cannot cancel: rpc=%s, session=%s, status=%s",
    										op.opId, sessionEndpoint, resp.getState());
    		return ResponseEntity.status(HttpStatus.CONFLICT)
    								.body(RESTfulErrorEntity.ofMessage(details));
    	}

    	return ResponseEntity.ok().body(resp);
    }

    /**
     * 요청 처리 중 발생한 예외를 오류 응답으로 바꾼다.
     * <p>
     * 클라이언트 잘못인 {@code 4xx}는 WARN 한 줄로, 서버 오류인 {@code 5xx}만 ERROR와 스택으로 기록한다.
     * 2026-11-08 이전에는 모든 예외를 ERROR와 스택으로 기록했다(검토 I2).
     *
     * @param e	발생한 예외.
     * @return	{@link RESTfulErrorEntity}를 본문으로 갖는 오류 응답.
     */
    @ExceptionHandler()
    public ResponseEntity<RESTfulErrorEntity> handleException(Exception e) {
    	HttpStatusCode status;
    	Throwable cause;
    	if ( e instanceof HttpMessageNotReadableException ) {
    		// 요청 본문이 JSON이 아니거나 RpcRequestMessage 형식이 아닌 경우.
    		status = HttpStatus.BAD_REQUEST;
    		cause = e;
    	}
    	else if ( e instanceof ErrorResponse er ) {
    		// Spring MVC가 정한 상태 코드(415 Unsupported Media Type, 405 Method Not Allowed 등)를 따른다.
    		status = er.getStatusCode();
    		cause = e;
    	}
    	else {
    		cause = Throwables.unwrapThrowable(e);
    		status = toHttpStatus(cause);
    	}

    	if ( status.is5xxServerError() ) {
    		s_logger.error("failed to handle request", cause);
    	}
    	else {
    		s_logger.warn("request rejected: status={}, cause={}", status.value(), cause.toString());
    	}
    	return ResponseEntity.status(status).body(RESTfulErrorEntity.of(cause));
    }

    private static HttpStatus toHttpStatus(Throwable cause) {
    	if ( cause instanceof IllegalArgumentException ) {
    		return HttpStatus.BAD_REQUEST;
    	}
    	else if ( cause instanceof ResourceNotFoundException ) {
    		return HttpStatus.NOT_FOUND;
    	}
    	else if ( cause instanceof TimeoutException ) {
    		return HttpStatus.REQUEST_TIMEOUT;
    	}
    	else {
    		return HttpStatus.INTERNAL_SERVER_ERROR;
    	}
    }

    /**
     * 세션 식별자로부터 RPC 서버가 발급하는 것과 동일한 형식의 세션 엔드포인트를 만든다.
     *
     * @param sessionId	세션 식별자.
     * @return	세션 엔드포인트. (예: {@code /api/v1/sessions/{sessionId}})
     */
    private static String toSessionEndpoint(String sessionId) {
    	return String.format("%s/sessions/%s", API_PREFIX, sessionId);
    }

    /**
     * 세션 엔드포인트를 수행 중인 RPC 서버를 찾는다.
     * <p>
     * 등록된 서버들과 {@link #reload}로 교체된 서버들을 차례로 훑는다. 2026-11-08 이전에는 세션 → 서버를
     * 30초 만료 캐시에 담고 없을 때만 훑었다. 캐시는 정확성에 기여하지 않고, 만료 시간도 연산의 보존 시간과
     * 무관해 없앴다(검토 I3).
     *
     * @param sessionEndpoint	세션 엔드포인트(예: {@code /api/v1/sessions/{sessionId}}).
     * @return	해당 세션을 가진 RPC 서버 정보. 없으면 {@code null}.
     */
    private RegisteredOperation findOperationBySession(String sessionEndpoint) {
    	RegisteredOperation op = Funcs.findFirst(m_operations.values(),
											i -> i.server.getSession(sessionEndpoint) != null);
    	if ( op != null ) {
    		return op;
    	}
    	RetiredOperation retired = Funcs.findFirst(m_retiredOperations,
    											r -> r.operation.server.getSession(sessionEndpoint) != null);
    	return (retired != null) ? retired.operation : null;
	}

    private RegisteredOperation getOrLoadOperation(String opId) throws IOException {
    	RegisteredOperation op = findOrLoadOperation(opId);
    	if ( op == null ) {
    		throw new ResourceNotFoundException("Operation", "id=" + opId);
    	}
    	return op;
    }

	/**
	 * 연산 식별자에 해당하는 등록된 연산을 찾고, 없으면 {@code operations} 디렉터리에서 읽어 등록한다.
	 * <p>
	 * 같은 연산을 여러 요청이 동시에 처음 부르더라도 RPC 서버는 하나만 만들어진다
	 * ({@link ConcurrentHashMap#computeIfAbsent}). {@code operations} 디렉터리나 연산 디렉터리,
	 * {@code operation.json}이 없으면 {@code null}을 반환한다. 2026-11-08 이전에는 {@code operations}
	 * 디렉터리가 없으면 {@link IOException}을 던져 500이 되었고, 요청마다 디렉터리 목록을 다시 읽었다(검토 D6).
	 *
	 * @param opId	연산 식별자. {@code operations} 아래 디렉터리 이름이다.
	 * @return	등록된 RPC 서버 정보. 해당 연산이 없으면 {@code null}.
	 * @throws IllegalArgumentException	{@code opId}가 디렉터리 이름으로 쓸 수 없는 값(빈 값, {@code .},
	 * 									{@code ..}, 경로 구분자 포함)인 경우.
	 * @throws IOException	디스크립터 파일을 읽는 중 오류가 발생한 경우.
	 */
	RegisteredOperation findOrLoadOperation(String opId) throws IOException {
		RegisteredOperation op = m_operations.get(opId);
		if ( op != null ) {
			return op;
		}

		File descFile = getDescriptorFile(opId);
		if ( !descFile.isFile() ) {
			return null;
		}

		try {
			return m_operations.computeIfAbsent(opId, id -> {
				try {
					RESTfulCommandExecutionServer server = new RESTfulCommandExecutionServer(descFile, m_serde);
					s_logger.info("registered RPC operation: id={}, desc={}", id, descFile);
					return new RegisteredOperation(id, server);
				}
				catch ( IOException e ) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch ( UncheckedIOException e ) {
			throw e.getCause();
		}
	}

	/**
	 * {@code operations} 디렉터리의 모든 연산 디렉터리 중 아직 등록되지 않은 연산을 등록한다.
	 * 디스크립터를 읽지 못한 연산은 경고만 남기고 건너뛴다.
	 */
	private void loadAllOperations() {
		File[] opDirs = m_config.getOperationsDir().listFiles(File::isDirectory);
		if ( opDirs == null ) {
			return;
		}
		for ( File dir: opDirs ) {
			try {
				findOrLoadOperation(dir.getName());
			}
			catch ( IOException | IllegalArgumentException e ) {
				s_logger.warn("failed to load operation: id={}, cause={}", dir.getName(), e.toString());
			}
		}
	}

	/**
	 * 연산 식별자에 해당하는 디스크립터 파일 경로를 만든다. 파일이 있는지는 확인하지 않는다.
	 *
	 * @throws IllegalArgumentException	{@code opId}가 디렉터리 이름으로 쓸 수 없는 값인 경우.
	 */
	private File getDescriptorFile(String opId) {
		// 식별자를 그대로 경로에 붙이므로, operations 디렉터리 밖을 가리킬 수 있는 값은 거부한다.
		Preconditions.checkArgument(isValidOperationId(opId), "invalid operation id: '%s'", opId);
		return FileUtils.path(m_config.getOperationsDir(), opId, "operation.json");
	}

	private static boolean isValidOperationId(String opId) {
		return opId != null && !opId.isBlank()
				&& !opId.equals(".") && !opId.equals("..")
				&& opId.indexOf('/') < 0 && opId.indexOf('\\') < 0;
	}

	private void retire(RegisteredOperation op) {
		if ( op != null ) {
			m_retiredOperations.add(new RetiredOperation(op, Instant.now()));
		}
	}

	/**
	 * 교체된 서버 중 세션이 없고 교체된 지 {@link #RETIRED_SERVER_GRACE}가 지난 서버를 지운다.
	 * <p>
	 * 교체 직전에 그 서버로 시작 요청이 들어가 아직 세션이 등록되지 않았을 수 있으므로, 세션이 없더라도
	 * 바로 지우지 않고 유예 시간을 둔다.
	 */
	private void pruneRetiredOperations() {
		Instant due = Instant.now().minus(RETIRED_SERVER_GRACE);
		m_retiredOperations.removeIf(r -> r.retiredAt.isBefore(due) && !r.operation.server.hasSessions());
	}

	/**
	 * 등록된 연산들의 작업 디렉터리에 남은 세션 디렉터리를 지운다. 기동 시에만 부른다.
	 * <p>
	 * 서버가 비정상 종료되어 {@link #destroy()}가 불리지 못하면 세션 디렉터리가 남는다. 기동 시점에는 이
	 * 서버의 세션이 하나도 없으므로, 이름이 {@code session-<UUID>-<seq>} 형식인 디렉터리는 모두 이전 실행이 남긴
	 * 것이다. 같은 작업 디렉터리를 다른 서버 프로세스와 함께 쓰는 구성은 지원하지 않는다.
	 */
	private void removeStaleSessionDirectories() {
		FStream.from(m_operations.values())
				.map(op -> op.server.getWorkingDirectory())
				.distinct()
				.forEach(workDir -> {
					File[] stale = workDir.listFiles(f -> f.isDirectory()
														&& SESSION_DIR_NAME.matcher(f.getName()).matches());
					if ( stale == null ) {
						return;
					}
					for ( File dir: stale ) {
						try {
							FileUtils.deleteDirectory(dir);
							s_logger.info("removed stale session directory: {}", dir);
						}
						catch ( IOException e ) {
							s_logger.warn("failed to remove stale session directory: {}, cause={}", dir, e.toString());
						}
					}
				});
	}
	
	private static OperationInfo toOperationInfo(RegisteredOperation op) {
		return OperationInfo.of(op.opId, op.server.getDescriptor());
	}
}
