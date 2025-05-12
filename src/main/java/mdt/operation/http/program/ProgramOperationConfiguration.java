package mdt.operation.http.program;

import java.io.File;
import java.time.Duration;
import java.util.List;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import utils.UnitUtils;
import utils.func.FOption;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
@JsonIncludeProperties({"commandLine", "workingDirectory", "async", "timeout", "sessionRetainTimeout",
						"currentExecution"})
@JsonInclude(Include.NON_NULL)
public class ProgramOperationConfiguration {
	private List<String> m_commandLine;
	@Nullable private File m_workingDirectory;
	private boolean m_async = true;
	@Nullable private Duration m_timeout;
	@Nullable private Duration m_sessionRetainTimeout;
	private boolean m_concurrentExecution = false;
	
	@JsonCreator
	public ProgramOperationConfiguration(@JsonProperty("commandLine") List<String> commandLine,
										@JsonProperty("workingDirectory") File workingDirectory,
										@JsonProperty("async") boolean async,
										@JsonProperty("timeout") String timeout,
										@JsonProperty("sessionRetainTimeout") Duration retainTimeout,
										@JsonProperty("concurrentExecution") boolean concurrent) {
		m_commandLine = commandLine;
		m_workingDirectory = workingDirectory;
		m_async = async;
		m_timeout = UnitUtils.parseDuration(timeout);
		m_sessionRetainTimeout = retainTimeout;
		m_concurrentExecution = concurrent;
	}
	
	public List<String> getCommandLine() {
		return m_commandLine;
	}
	
	public File getWorkingDirectory() {
		return m_workingDirectory;
	}
	
	public void setWorkingDirectory(File dir) {
		m_workingDirectory = dir;
	}
	
	public boolean getAsync() {
		return m_async;
	}
	
	public Duration getTimeout() {
		return m_timeout;
	}
	
	public Duration getSessionRetainTimeout() {
		return m_sessionRetainTimeout;
	}
	
	public void setTimeout(Duration duration) {
		m_timeout = duration;
	}
	
	@JsonProperty("timeout")
	public void setTimeoutForJackson(String durStr) {
		m_timeout = UnitUtils.parseDuration(durStr);
	}
	
	public void setSessionRetainTimeout(String durStr) {
		m_sessionRetainTimeout = UnitUtils.parseDuration(durStr);
	}
	
	public boolean isConcurrentExecution() {
		return m_concurrentExecution;
	}
	
	@Override
	public String toString() {
		String workingDirStr = FOption.mapOrElse(getWorkingDirectory(),
												f -> String.format(", working-dir=%s", f), "");
		return String.format("command=%s%s", m_commandLine, workingDirStr);
	}
}
