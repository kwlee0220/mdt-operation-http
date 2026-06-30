package mdt.operation.servers;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;


/**
 * 설정 프로퍼티 빈({@link RpcServersConfiguration})을 활성화하는 Spring 구성 클래스.
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Configuration
@EnableConfigurationProperties({
	RpcServersConfiguration.class,
})
public class ApplicationConfiguration {
}
