package mdt.operation.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


/**
 * MDT 연산을 RESTful RPC로 노출하는 연산 서버({@link OperationController})의 Spring Boot 진입점.
 * 2026-11-08 이전 이름은 {@code mdt.operation.servers.RpcServerDispatcherApplication}였다(검토 N1·N2).
 * <p>
 * 설정 프로퍼티 클래스({@link OperationServerProperties})는 {@link ConfigurationPropertiesScan}으로 등록한다.
 * 2026-11-08 이전에는 이 일만 하는 {@code ApplicationConfiguration} 클래스가 따로 있었다(검토 I8).
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MDTOperationServerApplication {
	public static void main(String[] args) throws Exception {
        SpringApplication.run(MDTOperationServerApplication.class, args);
	}
}
