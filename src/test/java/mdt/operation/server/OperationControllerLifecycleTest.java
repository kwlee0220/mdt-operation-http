package mdt.operation.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

import utils.rpc.restful.RpcRequestMessage;
import utils.rpc.restful.RpcResponseMessage;
import utils.rpc.restful.RpcState;

import mdt.model.MDTModelSerDe;
import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;


/**
 * {@link OperationController}로 연산을 실제로 실행하는 정상 경로 테스트(검토 I5).
 * <p>
 * 연산은 {@code /bin/sh}·{@code /bin/sleep}을 실행하므로 Linux에서만 수행된다. MDT 참조 입력은 쓰지 않으므로
 * 인스턴스 관리자는 Mockito로 대신한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationControllerLifecycleTest {
	@Rule public TemporaryFolder m_temp = new TemporaryFolder();

	private OperationController m_controller;
	private File m_opsDir;

	@Before
	public void setup() throws Exception {
		assumeTrue("Linux 환경에서만 동작", System.getProperty("os.name").toLowerCase().contains("linux"));

		m_opsDir = m_temp.newFolder("operations");
		writeOperation("echo", "{\"commandLine\":[\"/bin/sh\",\"-c\",\"cat ${data:path} > ${result:path}\"],"
								+ "\"outputVariables\":[\"result\"],\"timeout\":\"30s\"}");
		writeOperation("sleep", "{\"commandLine\":[\"/bin/sleep\",\"10\"],\"timeout\":\"30s\"}");

		m_controller = new OperationController();
		OperationServerProperties config = mock(OperationServerProperties.class);
		when(config.getOperationsDir()).thenReturn(m_opsDir);
		OperationControllerTest.setField(m_controller, "m_config", config);
		OperationControllerTest.setField(m_controller, "m_serde",
										new MDTCommandVariableSerDe(mock(MDTInstanceManager.class)));
	}

	@Test
	public void testRunToCompletion() throws Exception {
		// 시작(202) → 상태 polling → COMPLETED와 출력 → 결과는 한 번만 돌려주므로 다시 조회하면 404.
		RpcRequestMessage req = new RpcRequestMessage(Map.of("data", json("{\"v\":1}")));
		ResponseEntity<RpcResponseMessage> started = m_controller.startOperation("echo", req);
		assertEquals(HttpStatus.ACCEPTED, started.getStatusCode());
		// 명령이 바로 끝나도 시작 응답은 RUNNING이고, 결과는 상태 조회로 받는다(검토 D7).
		assertEquals(RpcState.RUNNING, started.getBody().getState());
		String sid = toSessionId(started.getBody());

		RpcResponseMessage resp = waitForTermination(sid);
		assertEquals(RpcState.COMPLETED, resp.getState());
		assertEquals(json("{\"v\":1}"), resp.getOutputs().get("result"));

		assertThrows(ResourceNotFoundException.class, () -> m_controller.getSessionStatus(sid));
	}

	@Test
	public void testCancel() throws Exception {
		ResponseEntity<RpcResponseMessage> started = m_controller.startOperation("sleep", new RpcRequestMessage(Map.of()));
		String sid = toSessionId(started.getBody());
		assertEquals(RpcState.RUNNING, m_controller.getSessionStatus(sid).getState());

		ResponseEntity<?> cancelled = m_controller.cancelSession(sid);
		assertEquals(HttpStatus.OK, cancelled.getStatusCode());
		assertEquals(RpcState.CANCELLED, ((RpcResponseMessage)cancelled.getBody()).getState());
	}

	@Test
	public void testInvalidInputFailsAtStart() throws Exception {
		// 입력 오류는 세션 없이 202 + FAILED로 응답한다(검토 D3: 호출 측 클라이언트 계약).
		RpcRequestMessage req = new RpcRequestMessage(Map.of("data", json("{\"@type\":\"foo:bar\"}")));
		ResponseEntity<RpcResponseMessage> resp = m_controller.startOperation("echo", req);

		assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
		assertEquals(RpcState.FAILED, resp.getBody().getState());
		assertNull(resp.getBody().getSessionEndpoint());
		assertEquals(IllegalArgumentException.class.getName(), resp.getBody().getError().getCode());
	}

	@Test
	public void testListAndGetOperation() throws Exception {
		List<OperationController.OperationInfo> ops = m_controller.listOperations();
		assertEquals(List.of("echo", "sleep"), ops.stream().map(OperationController.OperationInfo::id).toList());

		OperationController.OperationInfo echo = m_controller.getOperation("echo");
		assertEquals(List.of("result"), echo.outputVariables());
		assertEquals("PT30S", echo.timeout());
		assertNull(echo.startTimeout());

		assertThrows(ResourceNotFoundException.class, () -> m_controller.getOperation("none"));
	}

	@Test
	public void testReloadKeepsRunningSession() throws Exception {
		// reload 전에 시작한 세션은 이전 서버에 남아 계속 조회·취소할 수 있고, 새 요청은 새 디스크립터를 쓴다.
		ResponseEntity<RpcResponseMessage> started = m_controller.startOperation("sleep", new RpcRequestMessage(Map.of()));
		String sid = toSessionId(started.getBody());

		writeOperation("sleep", "{\"commandLine\":[\"/bin/sleep\",\"10\"],\"timeout\":\"45s\"}");
		OperationController.OperationInfo reloaded = m_controller.reload("sleep");
		assertEquals("PT45S", reloaded.timeout());
		assertEquals("PT45S", m_controller.getOperation("sleep").timeout());

		assertEquals(RpcState.RUNNING, m_controller.getSessionStatus(sid).getState());
		assertEquals(HttpStatus.OK, m_controller.cancelSession(sid).getStatusCode());
	}

	@Test
	public void testReloadRemovedOperation() throws Exception {
		m_controller.getOperation("echo");
		Files.delete(new File(new File(m_opsDir, "echo"), "operation.json").toPath());

		assertThrows(ResourceNotFoundException.class, () -> m_controller.reload("echo"));
		assertFalse(m_controller.listOperations().stream().anyMatch(op -> op.id().equals("echo")));
	}

	@Test
	public void testDestroyCancelsRunningSessions() throws Exception {
		// 서버 종료(destroy) 시 실행 중인 연산을 취소하고 세션 디렉터리를 지운다(검토 I7).
		ResponseEntity<RpcResponseMessage> started = m_controller.startOperation("sleep", new RpcRequestMessage(Map.of()));
		String sid = toSessionId(started.getBody());
		File sleepDir = new File(m_opsDir, "sleep");

		m_controller.destroy();
		assertEquals(RpcState.CANCELLED, m_controller.getSessionStatus(sid).getState());
		long deadline = System.currentTimeMillis() + 2000;
		while ( sleepDir.listFiles((d, n) -> n.startsWith("session-")).length > 0
				&& System.currentTimeMillis() < deadline ) {
			Thread.sleep(50);
		}
		assertEquals(0, sleepDir.listFiles((d, n) -> n.startsWith("session-")).length);
	}

	private void writeOperation(String opId, String descJson) throws IOException {
		File opDir = new File(m_opsDir, opId);
		opDir.mkdirs();
		Files.writeString(new File(opDir, "operation.json").toPath(), descJson, StandardCharsets.UTF_8);
	}

	private static String toSessionId(RpcResponseMessage resp) {
		assertNotNull("session endpoint", resp.getSessionEndpoint());
		String prefix = OperationController.API_PREFIX + "/sessions/";
		assertEquals(true, resp.getSessionEndpoint().startsWith(prefix));
		return resp.getSessionEndpoint().substring(prefix.length());
	}

	private RpcResponseMessage waitForTermination(String sid) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		RpcResponseMessage resp = m_controller.getSessionStatus(sid);
		while ( resp.getState() == RpcState.RUNNING && System.currentTimeMillis() < deadline ) {
			Thread.sleep(50);
			resp = m_controller.getSessionStatus(sid);
		}
		return resp;
	}

	private static JsonNode json(String literal) throws IOException {
		return MDTModelSerDe.getJsonMapper().readTree(literal);
	}
}
