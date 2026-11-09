package mdt.operation.server;

import java.io.File;
import java.io.IOException;

import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelElement;
import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.JsonNode;

import utils.KeyValue;
import utils.Preconditions;
import utils.async.command.CommandVariable;
import utils.rpc.restful.process.CommandVariableSerDe;

import mdt.model.MDTModelSerDe;
import mdt.model.instance.MDTInstanceManager;
import mdt.model.sm.ref.ElementReference;
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
	 * <p>
	 * 입력 값 자체가 잘못된 경우는 {@link IllegalArgumentException}으로, 값을 읽거나 파일에 기록하는 중의
	 * 오류는 {@link IOException}으로 구분한다. 2026-11-08 이전에는 입력 오류도 모두 {@code IOException}
	 * ("Failed to write value to file")으로 보고되었다(검토 D3).
	 *
	 * @throws IllegalArgumentException	{@code id}/{@code cvDir}/{@code jnode}가 {@code null}이거나
	 * 									{@code cvDir}가 디렉토리가 아닌 경우, {@code @type}이 알 수 없는 값이거나
	 * 									MDT 요소 참조가 아닌 참조인 경우, 파일 값({@link FileValue},
	 * 									AAS {@code File})을 값으로 직접 준 경우.
	 * @throws IOException	참조 값을 읽거나 값을 변환·기록하는 중 오류가 발생한 경우.
	 */
	@Override
	public CommandVariable deserialize(String id, File cvDir, @NotNull JsonNode jnode) throws IOException {
		return deserialize(id, cvDir, jnode, false);
	}

	/**
	 * 출력 변수를 만든다. 입력({@link #deserialize(String, File, JsonNode)})과 같되, 출력 위치로 'File' 요소의 MDT 참조가 지정되면
	 * 참조 대상의 첨부 파일을 내려받지 않고, 명령이 끝난 뒤 결과 파일을 그 요소에 올리는 변수를 만든다
	 * ({@link MDTCommandVariable#MDTCommandVariable(String, File, MDTElementReference)}).
	 * <p>
	 * 2026-11-08 이전에는 출력도 입력과 같이 처리되어, 파일 출력은 결과를 돌려주지 못했다(검토 D2).
	 *
	 * @throws IllegalArgumentException	{@link #deserialize(String, File, JsonNode)}와 같다.
	 * @throws IOException	{@link #deserialize(String, File, JsonNode)}와 같다.
	 */
	@Override
	public CommandVariable deserializeOutput(String id, File cvDir, @NotNull JsonNode jnode) throws IOException {
		return deserialize(id, cvDir, jnode, true);
	}

	private CommandVariable deserialize(String id, File cvDir, JsonNode jnode, boolean output)
		throws IOException {
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
				typeNode = jnode.get("modelType");
				if ( typeNode == null ) {
					// 일반 값인 경우는 '@type' 속성이 없음
					return deserializePlainValue(id, jnode, file);
				}
				else {	// AAS SubmodelElement인 경우는 'modelType' 속성이 있음
					return deserializeSubmodelElement(id, jnode, file);
				}
			}
			else if ( typeNode.asText().startsWith("mdt:ref:") ) {
				// ElementReference인 경우는 '@type' 속성이 'mdt:ref:'로 시작
				return deserializeReference(id, jnode, file, output);
			}
			else if ( typeNode.asText().startsWith("mdt:value:") ) {
				// ElementValue인 경우는 '@type' 속성이 'mdt:value:'로 시작
				return deserializeElementValue(id, jnode, file);
			}
			else {
				throw new IllegalArgumentException("unknown @type of command variable: name=" + id
													+ ", @type=" + typeNode.asText());
			}
		}
		catch ( IOException e ) {
			throw new IOException("failed to prepare command variable: name=" + id
										+ ", path=" + file.getAbsolutePath() + ", cause=" + e.getMessage(), e);
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
	
	private MDTCommandVariable deserializePlainValue(String id, JsonNode jnode, File file)
		throws IOException {
		String str = MDTModelSerDe.getJsonMapper().writeValueAsString(jnode);
		return new MDTCommandVariable(id, str, file, (ElementValue)null);
	}
	
	private MDTCommandVariable deserializeReference(String id, JsonNode jnode, File file, boolean output)
		throws IOException {
		ElementReference parsed = ElementReferences.parseJsonNode(jnode);
		if ( !(parsed instanceof MDTElementReference ref) ) {
			// 2026-11-08 이전에는 바로 캐스트하여 ClassCastException이 났다(검토 D3).
			throw new IllegalArgumentException("not an MDT element reference: name=" + id
												+ ", reference=" + parsed);
		}
		ref.activate(m_manager);
		
		// reference에 해당하는 값을 읽어서 ElementValue의 타입을 확인한다.
		// AAS FileValue 인 경우: 입력이면 첨부 파일을 내려받아 명령 변수 파일에 저장하고, 출력이면 내려받지 않고
		// 명령이 끝난 뒤 결과 파일을 올릴 변수를 만든다.
		// 일반 ElementValue인 경우는 value-object를 JSON 문자열로 변환하여 저장한다(출력이면 값 해석용 프로토타입).
		ElementValue smev = ref.readValue();
		if ( smev instanceof FileValue && output ) {
			return new MDTCommandVariable(id, file, ref);
		}
		else if ( smev instanceof FileValue ) {
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
			throw new IllegalArgumentException("FileValue is not allowed for command variable: name=" + id);
		}
		
		String str = MDTModelSerDe.getJsonMapper().writeValueAsString(smev.toValueObject());
		return new MDTCommandVariable(id, str, file, smev);
	}
	
	private MDTCommandVariable deserializeSubmodelElement(String id, JsonNode jnode, File file) throws IOException {
		SubmodelElement sme = MDTModelSerDe.readValue(jnode, SubmodelElement.class);
		ElementValue smev = ElementValues.getValue(sme);
		if ( smev instanceof FileValue ) {
			throw new IllegalArgumentException("File is not allowed for command variable: name=" + id);
		}
		
		String str = MDTModelSerDe.getJsonMapper().writeValueAsString(smev.toValueObject());
		return new MDTCommandVariable(id, str, file, smev);
	}
}
