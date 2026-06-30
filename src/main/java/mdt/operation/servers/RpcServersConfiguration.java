package mdt.operation.servers;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import utils.Preconditions;
import utils.io.FileUtils;


/**
 * RPC 연산 서버 디스패처({@link RpcServerDispatcher})의 설정 프로퍼티({@code rpc-servers.*}).
 * <p>
 * 연산 디스크립터들이 위치한 {@code operationsDir}와 MDT 인스턴스 관리자 접속 URL
 * ({@code instanceManagerUrl})을 보유한다. {@code operationsDir}의 기본값은 현재 작업 디렉토리 아래
 * {@code operations} 디렉토리이다.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "rpc-servers")
public class RpcServersConfiguration {
	private File m_operationsDir;
	private String m_instanceManagerUrl;

	RpcServersConfiguration() {
		m_operationsDir = new File(FileUtils.getCurrentWorkingDirectory(), "operations");
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
	 * MDT 인스턴스 관리자 접속 URL을 반환한다.
	 *
	 * @return	인스턴스 관리자 URL. 설정되지 않았으면 {@code null}.
	 */
	public String getInstanceManagerUrl() {
		return m_instanceManagerUrl;
	}

	/**
	 * MDT 인스턴스 관리자 접속 URL을 설정한다.
	 *
	 * @param instanceManagerUrl	인스턴스 관리자 URL. ({@code null} 불가)
	 * @throws IllegalArgumentException	{@code instanceManagerUrl}가 {@code null}인 경우.
	 */
	public void setInstanceManagerUrl(String instanceManagerUrl) {
		Preconditions.checkNotNullArgument(instanceManagerUrl,
											"instanceManagerUrl must be specified");
		m_instanceManagerUrl = instanceManagerUrl;
	}
}
