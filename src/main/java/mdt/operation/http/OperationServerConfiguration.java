package mdt.operation.http;

import java.io.File;

import lombok.Data;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Data
public class OperationServerConfiguration {
	private String endpoint;
	private File operations;
	private File homeDir;
}
