package mdt.operation.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.value.FileValue;


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
		assertThrows(IllegalArgumentException.class,
					() -> m_serde.deserialize("x", m_dir, json("{\"@type\":\"foo:bar\"}")));
	}

	@Test
	public void testDeserializeFileSubmodelElementRejected() {
		// 파일 값은 값으로 직접 줄 수 없고 MDT 요소 참조로만 줄 수 있다. 입력 오류이므로 IllegalArgumentException.
		String fileSme = "{\"modelType\":\"File\",\"idShort\":\"f\",\"contentType\":\"text/plain\",\"value\":\"a.txt\"}";
		assertThrows(IllegalArgumentException.class, () -> m_serde.deserialize("x", m_dir, json(fileSme)));
	}

	// ----- 파일 출력 -----

	@Test
	public void testFileOutputUploadsResultFile() throws IOException {
		// 'File' 요소 참조로 지정된 출력: 명령이 기록한 (바이너리) 파일을 참조 대상에 올리고, 갱신된
		// FileValue를 돌려준다. 이전에는 출력 파일 내용을 JSON으로 파싱하려다 실패했다(검토 D2).
		File outFile = new File(m_dir, "Image");
		MDTElementReference ref = mock(MDTElementReference.class);
		FileValue uploaded = new FileValue("Image", "image/png");
		when(ref.updateAttachment(outFile)).thenReturn(uploaded);

		MDTCommandVariable var = new MDTCommandVariable("Image", outFile, ref);
		Files.write(new File(var.getValueByModifier("path")).toPath(),
					new byte[] { (byte)0x89, 'P', 'N', 'G', 0, 1, 2 });

		KeyValue<String,JsonNode> kv = m_serde.serialize(var);
		assertEquals("Image", kv.key());
		assertEquals(uploaded.toJsonNode(), kv.value());
		verify(ref).updateAttachment(outFile);
	}

	@Test
	public void testFileOutputWithoutResultFileFails() throws IOException {
		// 명령이 출력 파일을 기록하지 않으면 올릴 파일이 없으므로 실패한다.
		File outFile = new File(m_dir, "Image");
		MDTElementReference ref = mock(MDTElementReference.class);
		when(ref.updateAttachment(outFile)).thenThrow(new IOException("no such file"));

		MDTCommandVariable var = new MDTCommandVariable("Image", outFile, ref);
		assertThrows(IOException.class, () -> m_serde.serialize(var));
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
		CommandVariable plain = CommandVariable.input("plain", "v", f);

		assertThrows(IllegalArgumentException.class, () -> m_serde.serialize(plain));
	}
}
