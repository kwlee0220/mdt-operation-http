package mdt.operation.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import utils.KeyValue;
import utils.async.command.CommandVariable;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstanceManager;


/**
 * {@link MDTCommandVariableSerDe} 역직렬화/직렬화 테스트.
 * <p>
 * MDT 요소 참조({@code mdt:ref:*})/{@link mdt.model.sm.value.ElementValue}({@code mdt:value:*}) 경로는
 * 실제 MDT 인스턴스 인프라가 필요하므로, 인프라 없이 검증 가능한 일반 값 경로와 인자 검증을 다룬다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTCommandVariableSerDeTest {
	private static final JsonMapper MAPPER = MDTModelSerDe.getJsonMapper();

	@Rule public TemporaryFolder m_temp = new TemporaryFolder();

	private MDTInstanceManager m_manager;
	private MDTCommandVariableSerDe m_serde;
	private File m_dir;

	@Before
	public void setup() throws IOException {
		m_manager = mock(MDTInstanceManager.class);
		m_serde = new MDTCommandVariableSerDe(m_manager);
		m_dir = m_temp.newFolder("cvars");
	}

	private JsonNode json(String literal) throws IOException {
		return MAPPER.readTree(literal);
	}

	// ----- deserialize: 일반 값 (@type 없음) -----

	@Test
	public void testDeserializePlainValueProducesMDTCommandVariable() throws IOException {
		CommandVariable var = m_serde.deserialize("x", m_dir, json("{\"v\":7}"));
		assertTrue(var instanceof MDTCommandVariable);
		assertEquals("x", var.getName());
	}

	@Test
	public void testPlainValueRoundTrip() throws IOException {
		JsonNode input = json("{\"v\":7,\"s\":\"hello\"}");

		CommandVariable var = m_serde.deserialize("x", m_dir, input);
		KeyValue<String,JsonNode> kv = m_serde.serialize(var);

		assertEquals("x", kv.key());
		assertEquals(input, kv.value());
	}

	// ----- deserialize: 인자 검증 -----

	@Test
	public void testDeserializeNullJsonNodeRejected() {
		assertThrows(IllegalArgumentException.class, () -> m_serde.deserialize("x", m_dir, null));
	}

	@Test
	public void testDeserializeNullIdRejected() {
		assertThrows(IllegalArgumentException.class,
					() -> m_serde.deserialize(null, m_dir, mock(JsonNode.class)));
	}

	@Test
	public void testDeserializeNullDirRejected() {
		assertThrows(IllegalArgumentException.class,
					() -> m_serde.deserialize("x", null, mock(JsonNode.class)));
	}

	@Test
	public void testDeserializeNonDirectoryRejected() throws IOException {
		File notDir = m_temp.newFile("regular-file");
		assertThrows(IllegalArgumentException.class,
					() -> m_serde.deserialize("x", notDir, mock(JsonNode.class)));
	}

	@Test
	public void testDeserializeUnknownTypeRejected() {
		assertThrows(IOException.class,
					() -> m_serde.deserialize("x", m_dir, json("{\"@type\":\"foo:bar\"}")));
	}

	// ----- serialize: 인자 검증 -----

	@Test
	public void testSerializeNullRejected() {
		assertThrows(IllegalArgumentException.class, () -> m_serde.serialize(null));
	}

	@Test
	public void testSerializeNonMDTCommandVariableRejected() throws IOException {
		File f = new File(m_dir, "plain");
		Files.writeString(f.toPath(), "v");
		CommandVariable plain = new CommandVariable("plain", "v", f);

		assertThrows(IllegalArgumentException.class, () -> m_serde.serialize(plain));
	}
}
