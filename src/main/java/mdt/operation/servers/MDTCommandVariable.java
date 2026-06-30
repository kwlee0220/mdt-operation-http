package mdt.operation.servers;

import java.io.File;
import java.io.IOException;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import utils.async.command.CommandVariable;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.ref.ElementReference;
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.FileValue;

/**
 * MDT 모델 값/참조를 결합한 명령 실행 변수({@link CommandVariable}).
 * <p>
 * 일반 명령 변수에 더해, 값의 형태를 해석하기 위한 {@link ElementValue} 프로토타입({@link #getPrototype()})
 * 또는 출력을 기록할 MDT 요소 참조({@link MDTElementReference})를 보유한다. 직렬화 시
 * {@link #toJsonNode()}가 이 정보를 이용해 변수의 현재 값을 적절한 MDT 모델 JSON으로 변환한다.
 * <ul>
 *   <li>참조 보유 — 참조 대상에 값을 기록(또는 {@link FileValue}면 첨부 파일을 갱신)한 뒤 그 값을 JSON으로 반환.</li>
 *   <li>프로토타입 보유 — 변수 값을 프로토타입 형태의 {@link ElementValue}로 파싱하여 JSON으로 반환.</li>
 *   <li>둘 다 없음 — 변수 값을 일반 JSON으로 파싱하여 반환.</li>
 * </ul>
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTCommandVariable extends CommandVariable {
	@Nullable private final ElementValue m_prototype;
	@Nullable private final ElementReference m_reference;	// 'file' type output variable인 경우만 non-null

	/**
	 * 값과 프로토타입을 갖는 명령 변수를 생성한다.
	 *
	 * @param name	변수 이름.
	 * @param value	변수 값(JSON 문자열).
	 * @param file	변수 값이 기록될 파일.
	 * @param proto	값 해석에 사용할 {@link ElementValue} 프로토타입. ({@code null} 허용)
	 * @throws IOException	상위 {@link CommandVariable} 생성 중 입출력 오류가 발생한 경우.
	 */
	public MDTCommandVariable(String name, String value, File file, ElementValue proto) throws IOException {
		super(name, value, file);

		m_prototype = proto;
		m_reference = null;
	}

	/**
	 * 값을 파일에서 읽는 명령 변수를 생성한다. (값이 파일로 제공되는 {@link FileValue} 등)
	 *
	 * @param name	변수 이름.
	 * @param file	변수 값이 담긴 파일.
	 * @param proto	값 해석에 사용할 {@link ElementValue} 프로토타입. ({@code null} 허용)
	 * @throws IOException	상위 {@link CommandVariable} 생성 중 입출력 오류가 발생한 경우.
	 */
	public MDTCommandVariable(String name, File file, ElementValue proto) throws IOException {
		super(name, file);

		m_prototype = proto;
		m_reference = null;
	}

	/**
	 * MDT 요소 참조에 출력을 기록하는 명령 변수를 생성한다.
	 *
	 * @param name	변수 이름.
	 * @param value	변수 값(JSON 문자열).
	 * @param file	변수 값이 기록될 파일.
	 * @param ref	출력을 기록할 MDT 요소 참조.
	 * @throws IOException	상위 {@link CommandVariable} 생성 중 입출력 오류가 발생한 경우.
	 */
	public MDTCommandVariable(String name, String value, File file, MDTElementReference ref)
		throws IOException {
		super(name, value, file);

		m_prototype = null;
		m_reference = ref;
	}

	/**
	 * 값 해석에 사용하는 {@link ElementValue} 프로토타입을 반환한다.
	 *
	 * @return	프로토타입. 지정되지 않았으면 {@code null}.
	 */
	public ElementValue getPrototype() {
		return m_prototype;
	}

	/**
	 * 변수의 현재 값을 MDT 모델 JSON으로 변환한다.
	 * <p>
	 * 요소 참조를 보유하면 참조 대상에 값을 갱신(또는 {@link FileValue}면 첨부 파일을 갱신)한 뒤 그 값을,
	 * 프로토타입을 보유하면 값을 프로토타입 형태로 파싱한 값을, 둘 다 없으면 값을 일반 JSON으로 파싱하여
	 * 반환한다.
	 *
	 * @return	변환된 JSON 노드.
	 * @throws IOException	값 읽기/파싱 또는 참조 갱신 중 입출력 오류가 발생한 경우.
	 */
	public JsonNode toJsonNode() throws IOException {
		String strValue = getValue();
		if ( m_reference != null ) {
			ElementValue smev = m_reference.readValue();
			if ( smev instanceof FileValue ) {
				smev = m_reference.updateAttachment(getFile());
			}
			else {
				smev = ElementValues.parseValueJsonString(strValue, smev);
				m_reference.updateValue(smev);
			}
			return smev.toJsonNode();
		}
		else if ( m_prototype != null ) {
			return ElementValues.parseValueJsonString(strValue, m_prototype)
								.toJsonNode();
		}
		else {
			return MDTModelSerDe.getJsonMapper().readTree(strValue);
		}
	}
}
