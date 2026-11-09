package mdt.operation.server;

import java.io.File;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import utils.Preconditions;
import utils.io.FileUtils;


/**
 * 연산 서버({@link OperationController})의 설정 프로퍼티({@code mdt.operation-server.*}).
 * <p>
 * 연산 디스크립터들이 위치한 {@code operationsDir}와 MDT 관리자 접속 URL({@code mdtManagerUrl})을 보유한다.
 * {@code operationsDir}의 기본값은 현재 작업 디렉토리 아래 {@code operations} 디렉토리이다.
 * <p>
 * {@code mdtManagerUrl}은 필수이며, 설정 바인딩이 끝나면({@link #afterPropertiesSet()}) 확인한다.
 * 2026-11-08 이전에는 이 확인을 디스패처가 기동할 때 했다(검토 I8).
 * <p>
 * 2026-11-08 이전 이름은 클래스 {@code RpcServersConfiguration}, 설정 키 {@code rpc-servers.instanceManagerUrl}·
 * {@code rpc-servers.operationsDir}였다. 별칭이 없으므로 옛 키는 읽지 않는다(검토 N3).
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "mdt.operation-server")
public class OperationServerProperties implements InitializingBean {
	private File m_operationsDir;
	private String m_mdtManagerUrl;

	public OperationServerProperties() {
		m_operationsDir = new File(FileUtils.getCurrentWorkingDirectory(), "operations");
	}

	/**
	 * 필수 설정이 모두 있는지 확인한다. Spring이 설정 값을 바인딩한 뒤 부른다.
	 *
	 * @throws IllegalStateException	{@code mdt.operation-server.mdtManagerUrl}이 설정되지 않았거나 빈 값인 경우.
	 */
	@Override
	public void afterPropertiesSet() {
		Preconditions.checkState(m_mdtManagerUrl != null && !m_mdtManagerUrl.isBlank(),
								"mdt.operation-server.mdtManagerUrl is not configured");
	}

	/**
	 * 연산 디스크립터들이 위치한 디렉토리를 반환한다.
	 *
	 * @return	연산 디렉토리. 기본값은 현재 작업 디렉토리 아래 {@code operations}.
	 */
	public File getOperationsDir() {
		return m_operationsDir;
	}
	/**
	 * 연산 디스크립터들이 위치한 디렉토리를 설정한다.
	 *
	 * @param operationsDir	연산 디렉토리. ({@code null} 불가)
	 * @throws IllegalArgumentException	{@code operationsDir}가 {@code null}인 경우.
	 */
	public void setOperationsDir(File operationsDir) {
		Preconditions.checkNotNullArgument(operationsDir, "operationsDir must be specified");

		m_operationsDir = operationsDir;
	}

	/**
	 * MDT 관리자 접속 URL을 반환한다. 인스턴스 관리자는 이 URL로 얻는다({@code HttpMDTManager.connect}).
	 *
	 * @return	MDT 관리자 URL. 설정되지 않았으면 {@code null}.
	 */
	public String getMdtManagerUrl() {
		return m_mdtManagerUrl;
	}

	/**
	 * MDT 관리자 접속 URL을 설정한다.
	 *
	 * @param mdtManagerUrl	MDT 관리자 URL. ({@code null} 불가)
	 * @throws IllegalArgumentException	{@code mdtManagerUrl}가 {@code null}인 경우.
	 */
	public void setMdtManagerUrl(String mdtManagerUrl) {
		Preconditions.checkNotNullArgument(mdtManagerUrl, "mdtManagerUrl must be specified");
		m_mdtManagerUrl = mdtManagerUrl;
	}
}
