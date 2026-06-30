package mdt.operation.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import mdt.model.ResourceNotFoundException;


/**
 * {@link RpcServerDispatcher}의 not-found 라우팅 로직 테스트.
 * <p>
 * {@code spring-boot-starter-test}(MockMvc)에 의존하지 않고, 등록된 연산/세션이 없는 상태에서
 * 컨트롤러 메소드를 직접 호출하여 미존재 처리를 검증한다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RpcServerDispatcherTest {
	@Rule public TemporaryFolder m_temp = new TemporaryFolder();

	private RpcServerDispatcher m_dispatcher;

	@Before
	public void setup() {
		m_dispatcher = new RpcServerDispatcher();
	}

	@Test
	public void testStatusUnknownSessionReturns404() throws Exception {
		ResponseEntity<?> resp = m_dispatcher.status("unknown");
		assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatusCode().value());
	}

	@Test
	public void testDeleteUnknownSessionThrowsNotFound() {
		assertThrows(ResourceNotFoundException.class, () -> m_dispatcher.delete("unknown"));
	}

	@Test
	public void testRunUnknownOperationReturns404() throws Exception {
		File emptyOpsDir = m_temp.newFolder("operations");
		RpcServersConfiguration config = mock(RpcServersConfiguration.class);
		when(config.getOperationsDir()).thenReturn(emptyOpsDir);
		setField(m_dispatcher, "m_config", config);

		ResponseEntity<?> resp = m_dispatcher.run("unknown", "{\"inputs\":{}}");
		assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatusCode().value());
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = RpcServerDispatcher.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
