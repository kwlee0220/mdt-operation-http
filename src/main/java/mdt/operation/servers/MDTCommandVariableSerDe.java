package mdt.operation.servers;

import java.io.File;
import java.io.IOException;

import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.Preconditions;
import utils.async.command.CommandVariable;
import utils.rpc.restful.process.CommandVariableSerDe;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.ref.ElementReferences;
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.FileValue;

/**
 * MDT 모델 값/참조를 명령 실행 변수({@link MDTCommandVariable})로 상호 변환하는
 * {@link CommandVariableSerDe} 구현체.
 * <p>
 * 역직렬화 시 JSON의 {@code @type} 속성으로 입력 형태를 구분한다.
 * <ul>
 *   <li>{@code mdt:ref:*} — MDT 요소 참조. 참조 값을 읽어(또는 {@link FileValue}면 첨부 파일을 받아) 변수 파일에 기록.</li>
 *   <li>{@code mdt:value:*} — {@link ElementValue}. ({@code FileValue}는 허용하지 않음)</li>
 *   <li>속성 없음 — 일반 JSON 값.</li>
 * </ul>
 * 직렬화 시에는 변수의 현재 값을 다시 MDT 모델 JSON으로 변환한다({@link MDTCommandVariable#toJsonNode()}).
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTCommandVariableSerDe implements CommandVariableSerDe {
	private final MDTInstanceManager m_manager;

	/**
	 * SerDe를 생성한다.
	 *
	 * @param manager	MDT 요소 참조({@code mdt:ref:*})를 활성화할 때 사용하는 인스턴스 관리자.
	 */
	public MDTCommandVariableSerDe(MDTInstanceManager manager) {
		m_manager = manager;
	}

	/**
	 * 입력 JSON의 {@code @type} 속성으로 입력 형태(요소 참조/{@link ElementValue}/일반 값)를 구분하여
	 * {@link MDTCommandVariable}로 역직렬화한다.
	 *
	 * @throws IllegalArgumentException	{@code id}/{@code cvDir}/{@code jnode}가 {@code null}이거나
	 * 									{@code cvDir}가 디렉토리가 아닌 경우.
	 * @throws IOException	{@code @type}이 알 수 없는 값이거나 값 변환 중 입출력 오류가 발생한 경우.
	 */
	@Override
	public CommandVariable deserialize(String id, File cvDir, @NotNull JsonNode jnode) throws IOException {
		Preconditions.checkNotNullArgument(id, "id is null");
		Preconditions.checkNotNullArgument(cvDir, "cvDir is null");
		Preconditions.checkArgument(cvDir.isDirectory(), "cvDir is not a directory: " + cvDir.getAbsolutePath());
		
		Preconditions.checkNotNullArgument(jnode, "jnode is null");
		
		File file = new File(cvDir, id);
		try {
			// JSON node의 @type 속성을 확인하여, ElementReference ('mdt:ref:xxx')인지
			// ElementValue ('mdt:value')인지, 아니면 그냥 일반 값인진 판단한다.
			JsonNode typeNode = jnode.get("@type");
			if ( typeNode == null ) {
				// 일반 값인 경우는 '@type' 속성이 없음
				return deserializeValueObject(id, jnode, file);
			}
			else if ( typeNode.asText().startsWith("mdt:ref:") ) {
				// ElementReference인 경우는 '@type' 속성이 'mdt:ref:'로 시작
				return deserializeReference(id, jnode, file);
			}
			else if ( typeNode.asText().startsWith("mdt:value:") ) {
				// ElementValue인 경우는 '@type' 속성이 'mdt:value:'로 시작
				return deserializeElementValue(id, jnode, file);
			}
			else {
				throw new IOException("Invalid argument: name=" + id + ", value=" + typeNode.asText());
			}
		}
		catch ( IOException e ) {
			throw new IOException("Failed to write value to file: name=" + id
										+ ", path=" + file.getAbsolutePath(), e);
		}
	}

	/**
	 * 명령 변수의 현재 값을 MDT 모델 JSON({@link MDTCommandVariable#toJsonNode()})으로 직렬화한다.
	 *
	 * @throws IllegalArgumentException	{@code var}가 {@code null}이거나 {@link MDTCommandVariable}이
	 * 									아닌 경우.
	 */
	@Override
	public KeyValue<String,JsonNode> serialize(CommandVariable var) throws IOException {
		Preconditions.checkNotNullArgument(var, "var is null");
		Preconditions.checkArgument(var instanceof MDTCommandVariable, "var is not an instance of MDTCommandVariable: "
																		+ var.getClass().getName());
		
		return KeyValue.of(var.getName(), ((MDTCommandVariable)var).toJsonNode());
	}
	
	private MDTCommandVariable deserializeValueObject(String id, JsonNode jnode, File file)
		throws IOException {
		String str = MDTModelSerDe.getJsonMapper().writeValueAsString(jnode);
		return new MDTCommandVariable(id, str, file, (ElementValue)null);
	}
	
	private MDTCommandVariable deserializeReference(String id, JsonNode jnode, File file) throws IOException {
		MDTElementReference ref = (MDTElementReference)ElementReferences.parseJsonNode(jnode);
		ref.activate(m_manager);
		
		// reference에 해당하는 값을 읽어서 ElementValue의 타입을 확인한다.
		// AAS FileValue 인 경우는 파일에 저장된 content를 읽어와서 명령 변수 파일에 대신 저장한다.
		// 일반 ElementValue인 경우는 value-object를 JSON 문자열로 변환하여 저장한다.
		ElementValue smev = ref.readValue();
		if ( smev instanceof FileValue ) {
			ref.readAttachment(file);
			return new MDTCommandVariable(id, file, smev);
		}
		else {
			String str = MDTModelSerDe.getJsonMapper().writeValueAsString(smev.toValueObject());
			return new MDTCommandVariable(id, str, file, smev);
		}
	}
	
	private MDTCommandVariable deserializeElementValue(String id, JsonNode jnode, File file) throws IOException {
		ElementValue smev = ElementValues.parseJsonNode(jnode);
		if ( smev instanceof FileValue ) {
			throw new IOException("FileValue is not allowed for command variable: name=" + id
										+ ", path=" + file.getAbsolutePath());
		}
		
		String str = MDTModelSerDe.getJsonMapper().writeValueAsString(smev.toValueObject());
		return new MDTCommandVariable(id, str, file, smev);
	}
}
