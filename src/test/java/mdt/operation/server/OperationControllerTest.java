package mdt.operation.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;

import utils.rpc.restful.RpcRequestMessage;

import mdt.model.ResourceNotFoundException;
import mdt.model.instance.MDTInstanceManager;


/**
 * {@link OperationController}의 라우팅·오류 처리 테스트.
 * <p>
 * {@code spring-boot-starter-test}(MockMvc)에 의존하지 않고 컨트롤러 메소드를 직접 호출한다. 연산을 실제로
 * 실행하는 경로는 {@link OperationControllerLifecycleTest}에서 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationControllerTest {
	@Rule public TemporaryFolder m_temp = new TemporaryFolder();

	private OperationController m_controller;

	@Before
	public void setup() {
		m_controller = new OperationController();
	}

	@Test
	public void testStatusUnknownSessionThrowsNotFound() {
		// 세션이 없으면 status·delete 모두 ResourceNotFoundException(404)을 던진다(검토 I2).
		assertThrows(ResourceNotFoundException.class, () -> m_controller.getSessionStatus("unknown"));
	}

	@Test
	public void testDeleteUnknownSessionThrowsNotFound() {
		assertThrows(ResourceNotFoundException.class, () -> m_controller.cancelSession("unknown"));
	}

	@Test
	public void testRunUnknownOperationThrowsNotFound() throws Exception {
		setConfig(m_temp.newFolder("operations"));

		assertThrows(ResourceNotFoundException.class, () -> m_controller.startOperation("unknown", emptyRequest()));
	}

	@Test
	public void testRunWithMissingOperationsDirThrowsNotFound() throws Exception {
		// operations 디렉터리가 없어도 500이 아니라 404 (검토 D6)
		setConfig(new File(m_temp.getRoot(), "no-such-dir"));

		assertThrows(ResourceNotFoundException.class, () -> m_controller.startOperation("unknown", emptyRequest()));
	}

	@Test
	public void testRunWithInvalidOperationIdRejected() throws Exception {
		setConfig(m_temp.newFolder("operations"));

		for ( String opId: List.of("..", ".", " ", "a\\b") ) {
			assertThrows(opId, IllegalArgumentException.class, () -> m_controller.startOperation(opId, emptyRequest()));
		}
	}

	// ----- 예외 → 상태 코드 -----

	@Test
	public void testMalformedBodyMapsTo400() {
		// 본문 파싱 실패는 Spring이 HttpMessageNotReadableException으로 알린다(검토 I1, D3).
		HttpMessageNotReadableException e
				= new HttpMessageNotReadableException("bad body", mock(HttpInputMessage.class));
		assertEquals(HttpStatus.BAD_REQUEST, m_controller.handleException(e).getStatusCode());
	}

	@Test
	public void testExceptionStatusMapping() {
		assertEquals(HttpStatus.BAD_REQUEST,
					m_controller.handleException(new IllegalArgumentException("x")).getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND,
					m_controller.handleException(new ResourceNotFoundException("Operation", "id=x"))
								.getStatusCode());
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
					m_controller.handleException(new IllegalStateException("x")).getStatusCode());
	}

	// ----- 동시 등록 -----

	@Test
	public void testConcurrentFirstLoadCreatesSingleServer() throws Exception {
		// 처음 보는 연산을 여러 스레드가 동시에 불러도 RPC 서버는 하나만 만들어진다(검토 D1).
		File opsDir = m_temp.newFolder("operations");
		File opDir = new File(opsDir, "op");
		opDir.mkdirs();
		Files.writeString(new File(opDir, "operation.json").toPath(),
							"{\"commandLine\":[\"/bin/true\"]}", StandardCharsets.UTF_8);
		setConfig(opsDir);
		setField(m_controller, "m_serde", new MDTCommandVariableSerDe(mock(MDTInstanceManager.class)));

		int nthreads = 16;
		ExecutorService executor = Executors.newFixedThreadPool(nthreads);
		try {
			CountDownLatch ready = new CountDownLatch(1);
			List<Future<Object>> futures = new ArrayList<>();
			for ( int i = 0; i < nthreads; ++i ) {
				Callable<Object> task = () -> {
					ready.await();
					return m_controller.findOrLoadOperation("op");
				};
				futures.add(executor.submit(task));
			}
			ready.countDown();

			Object first = futures.get(0).get();
			for ( Future<Object> f: futures ) {
				assertSame(first, f.get());
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertNull(m_controller.findOrLoadOperation("other"));
	}

	private static RpcRequestMessage emptyRequest() {
		return new RpcRequestMessage(Map.of());
	}

	private void setConfig(File operationsDir) throws Exception {
		OperationServerProperties config = mock(OperationServerProperties.class);
		when(config.getOperationsDir()).thenReturn(operationsDir);
		setField(m_controller, "m_config", config);
	}

	static void setField(Object target, String name, Object value) throws Exception {
		Field field = OperationController.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
