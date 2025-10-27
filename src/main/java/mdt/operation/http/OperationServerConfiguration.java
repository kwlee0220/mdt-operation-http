package mdt.operation.http;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

import utils.io.FileUtils;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "operation-server")
@Getter @Setter
public class OperationServerConfiguration {
	private File homeDir = FileUtils.getCurrentWorkingDirectory();
	private String instanceManagerEndpoint;
}
