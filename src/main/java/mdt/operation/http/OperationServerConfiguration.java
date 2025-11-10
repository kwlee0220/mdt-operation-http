package mdt.operation.http;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import utils.func.FOption;
import utils.io.FileUtils;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@ConfigurationProperties(prefix = "operation-server")
@Accessors(prefix = "m_")
@Getter @Setter
public class OperationServerConfiguration {
	private File m_homeDir = FileUtils.getCurrentWorkingDirectory();
	private File m_operationsDir;
	private String m_instanceManagerEndpoint;
	
	public File getOperationsDir() {
		return FOption.getOrElse(m_operationsDir, () -> new File(m_homeDir, "operations"));
	}
}
