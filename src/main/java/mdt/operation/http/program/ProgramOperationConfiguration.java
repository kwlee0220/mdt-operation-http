package mdt.operation.http.program;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import lombok.Getter;
import lombok.Setter;

import utils.UnitUtils;
import utils.func.FOption;
import utils.stream.FStream;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@Getter @Setter
public class ProgramOperationConfiguration {
	private String id;
	private List<String> command;
	private String workingDirectory;
	private boolean runAsync = true;
	
	private Set<String> outputVariables;
	@Nullable private Duration timeout;
	@Nullable private Duration sessionRetainTimeout;
	
	public void setTimeout(String durStr) {
		timeout = UnitUtils.parseDuration(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		sessionRetainTimeout = UnitUtils.parseDuration(durStr);
	}
	
	@Override
	public String toString() {
		String outVarNames = FStream.from(outputVariables).join(',');
		String workingDirStr = FOption.mapOrElse(getWorkingDirectory(),
												f -> String.format(", working-dir=%s", f), "");
		return String.format("id=%s, command=%s%s, output-vars={%s}",
								this.id, this.command, workingDirStr, outVarNames);
	}
}
