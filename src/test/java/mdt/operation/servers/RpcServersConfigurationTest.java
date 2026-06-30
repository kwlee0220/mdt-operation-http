package mdt.operation.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;

import org.junit.Test;


/**
 * {@link RpcServersConfiguration} 프로퍼티 바인딩/검증 테스트.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class RpcServersConfigurationTest {
	@Test
	public void testDefaultOperationsDir() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		assertEquals("operations", config.getOperationsDir().getName());
	}

	@Test
	public void testSetOperationsDir() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		File dir = new File("/tmp/some-operations");
		config.setOperationsDir(dir);
		assertEquals(dir, config.getOperationsDir());
	}

	@Test
	public void testSetInstanceManagerUrl() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		config.setInstanceManagerUrl("http://localhost:12985");
		assertEquals("http://localhost:12985", config.getInstanceManagerUrl());
	}

	@Test
	public void testInstanceManagerUrlNullByDefault() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		assertEquals(null, config.getInstanceManagerUrl());
	}

	@Test
	public void testSetOperationsDirNullRejected() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		assertThrows(IllegalArgumentException.class, () -> config.setOperationsDir(null));
	}

	@Test
	public void testSetInstanceManagerUrlNullRejected() {
		RpcServersConfiguration config = new RpcServersConfiguration();
		assertThrows(IllegalArgumentException.class, () -> config.setInstanceManagerUrl(null));
	}
}
