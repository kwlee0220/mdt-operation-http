package mdt.operation.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.File;

import org.junit.Test;


/**
 * {@link OperationServerProperties} 프로퍼티 바인딩/검증 테스트.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class OperationServerPropertiesTest {
	@Test
	public void testDefaultOperationsDir() {
		OperationServerProperties config = new OperationServerProperties();
		assertEquals("operations", config.getOperationsDir().getName());
	}

	@Test
	public void testSetOperationsDir() {
		OperationServerProperties config = new OperationServerProperties();
		File dir = new File("/tmp/some-operations");
		config.setOperationsDir(dir);
		assertEquals(dir, config.getOperationsDir());
	}

	@Test
	public void testSetMdtManagerUrl() {
		OperationServerProperties config = new OperationServerProperties();
		config.setMdtManagerUrl("http://localhost:12985");
		assertEquals("http://localhost:12985", config.getMdtManagerUrl());
	}

	@Test
	public void testMdtManagerUrlNullByDefault() {
		OperationServerProperties config = new OperationServerProperties();
		assertEquals(null, config.getMdtManagerUrl());
	}

	@Test
	public void testSetOperationsDirNullRejected() {
		OperationServerProperties config = new OperationServerProperties();
		assertThrows(IllegalArgumentException.class, () -> config.setOperationsDir(null));
	}

	@Test
	public void testSetMdtManagerUrlNullRejected() {
		OperationServerProperties config = new OperationServerProperties();
		assertThrows(IllegalArgumentException.class, () -> config.setMdtManagerUrl(null));
	}

	@Test
	public void testMissingMdtManagerUrlRejectedAfterBinding() {
		// 설정 바인딩이 끝난 뒤 mdtManagerUrl이 없거나 빈 값이면 기동을 막는다(검토 I8).
		OperationServerProperties config = new OperationServerProperties();
		assertThrows(IllegalStateException.class, config::afterPropertiesSet);

		config.setMdtManagerUrl(" ");
		assertThrows(IllegalStateException.class, config::afterPropertiesSet);

		config.setMdtManagerUrl("http://localhost:12985");
		config.afterPropertiesSet();
	}
}
