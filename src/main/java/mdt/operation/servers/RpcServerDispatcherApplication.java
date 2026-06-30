package mdt.operation.servers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


/**
 * MDT 연산을 RESTful RPC로 노출하는 디스패처 서버({@link RpcServerDispatcher})의 Spring Boot 진입점.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@SpringBootApplication
public class RpcServerDispatcherApplication {
	public static void main(String[] args) throws Exception {
        SpringApplication.run(RpcServerDispatcherApplication.class, args);
	}
}
