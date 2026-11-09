package mdt.operation.server;

import java.io.File;
import java.io.IOException;

import org.jetbrains.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

import utils.async.command.CommandVariable;

import mdt.model.MDTModelSerDe;
import mdt.model.sm.ref.MDTElementReference;
import mdt.model.sm.value.ElementValue;
import mdt.model.sm.value.ElementValues;
import mdt.model.sm.value.FileValue;

/**
 * MDT 모델 값/참조를 결합한 명령 실행 변수({@link CommandVariable}).
 * <p>
 * 일반 명령 변수에 더해, 값의 형태를 해석하기 위한 {@link ElementValue} 프로토타입({@link #getPrototype()})
 * 또는 파일 출력을 올릴 MDT 요소 참조({@link MDTElementReference})를 보유한다. 직렬화 시
 * {@link #toJsonNode()}가 이 정보를 이용해 변수의 현재 값을 적절한 MDT 모델 JSON으로 변환한다.
 * <ul>
 *   <li>파일 출력 참조 보유 — 명령이 기록한 파일을 참조 대상 'File' 요소의 첨부 파일로 올리고, 갱신된
 *   	{@link FileValue}를 JSON으로 반환.</li>
 *   <li>프로토타입 보유 — 변수 값을 프로토타입 형태의 {@link ElementValue}로 파싱하여 JSON으로 반환.</li>
 *   <li>둘 다 없음 — 변수 값을 일반 JSON으로 파싱하여 반환.</li>
 * </ul>
 * 파일이 아닌 출력은 참조가 지정되어도 원격 모델에 기록하지 않는다. 파일 출력만 예외인 이유는, 세션
 * 디렉토리가 곧 지워지므로 서버가 올리지 않으면 호출자가 결과 파일을 받을 방법이 없기 때문이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTCommandVariable extends CommandVariable {
	@Nullable private final ElementValue m_prototype;
	@Nullable private final MDTElementReference m_fileOutputRef;	// 'File' 요소 참조로 지정된 출력 변수인 경우만 non-null

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
		super(name, value, file, true);

		m_prototype = proto;
		m_fileOutputRef = null;
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
		super(name, null, file, true);

		m_prototype = proto;
		m_fileOutputRef = null;
	}

	/**
	 * 명령이 기록한 파일을 MDT 'File' 요소의 첨부 파일로 올리는 출력 변수를 생성한다.
	 * <p>
	 * 변수는 파일 모드로 시작하며, 파일은 명령이 {@code ${name:path}}로 받은 경로에 기록한다. 결과를 받을
	 * 자리이므로 참조 대상의 현재 첨부 파일은 내려받지 않는다. 2026-11-08 이전에는 이 생성자를 쓰는 곳이 없어,
	 * 파일 출력이 입력과 같이 처리되어 원격 파일을 미리 내려받고, 끝난 뒤에는 바이너리 출력 파일을 JSON으로
	 * 파싱하려다 실패했다(검토 D2).
	 *
	 * @param name			변수 이름.
	 * @param file			명령이 결과를 기록할 파일.
	 * @param fileOutputRef	결과 파일을 올릴 'File' 요소의 참조. 활성화되어 있어야 한다.
	 * @throws IOException	상위 {@link CommandVariable} 생성 중 입출력 오류가 발생한 경우.
	 */
	public MDTCommandVariable(String name, File file, MDTElementReference fileOutputRef) throws IOException {
		super(name, null, file, true);

		m_prototype = null;
		m_fileOutputRef = fileOutputRef;
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
	 * 파일 출력 참조를 보유하면 명령이 기록한 파일을 참조 대상의 첨부 파일로 올린 뒤 갱신된 {@link FileValue}를,
	 * 프로토타입을 보유하면 값을 프로토타입 형태로 파싱한 값을, 둘 다 없으면 값을 일반 JSON으로 파싱하여
	 * 반환한다. 첨부 파일의 이름은 변수 파일 이름(변수 이름)이고, content type은 파일 내용으로 판별한다
	 * ({@link FileValue#of(File)}).
	 *
	 * @return	변환된 JSON 노드.
	 * @throws IOException	값 읽기/파싱 또는 첨부 파일 갱신 중 입출력 오류가 발생한 경우. 명령이 출력 파일을
	 * 						기록하지 않은 경우도 포함한다.
	 */
	public JsonNode toJsonNode() throws IOException {
		if ( m_fileOutputRef != null ) {
			FileValue uploaded = m_fileOutputRef.updateAttachment(getFile());
			return uploaded.toJsonNode();
		}
		
		String strValue = getValue();
		if ( m_prototype != null ) {
			return ElementValues.parseValueJsonString(strValue, m_prototype)
								.toJsonNode();
		}
		else {
			return MDTModelSerDe.getJsonMapper().readTree(strValue);
		}
	}
}
