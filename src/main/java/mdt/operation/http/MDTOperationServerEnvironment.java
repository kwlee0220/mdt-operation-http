package mdt.operation.http;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import utils.io.EnvironmentFileLoader;
import utils.stream.KeyValueFStream;

/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class MDTOperationServerEnvironment implements EnvironmentPostProcessor, Ordered {
    private static final DeferredLog s_deferredLog = new DeferredLog();

	private static final String ENV_FILE_ENV_VAR = "ENV_FILE";
	private static final String ENV_FILE_PROPERTY = "env.file";
	private static final String ENV_FILE_NAME = "config/env.file";
	
	private static Map<String,Object> s_environmentVariables = Map.of();
	
	public static Map<String,Object> getVariables() {
		return Collections.unmodifiableMap(s_environmentVariables);
	}
	
	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
        	Log actualLogger = LogFactory.getLog(MDTOperationServerEnvironment.class);
			s_deferredLog.replayTo(actualLogger);
		});
        
		String fromProp = System.getProperty(ENV_FILE_PROPERTY);
		String fromEnv = System.getenv(ENV_FILE_ENV_VAR);
		String path = firstNonBlank(fromProp, fromEnv, ENV_FILE_NAME);
		
		File envFile = new File(path);
		if ( !envFile.exists() ) {
			s_deferredLog.info("no environment file found at: " + envFile.getAbsolutePath());
			return;
		}

		s_deferredLog.info("loading environment variables from file: " + envFile.getAbsolutePath());
		try {
			EnvironmentFileLoader envLoader = EnvironmentFileLoader.from(new File(path));
			LinkedHashMap<String, String> variables = envLoader.load();

			s_environmentVariables
				= KeyValueFStream.from(variables)
								.peek(v -> s_deferredLog.info(String.format("  - EnvVar: %s=%s", v.key(), v.value())))
								.mapValue(v -> (Object)v)
								.toMap();
			MapPropertySource source = new MapPropertySource("mdtEnv", s_environmentVariables);
			env.getPropertySources().addFirst(source);
		}
		catch ( IOException e ) { }
	}
	
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}
	
	private static String firstNonBlank(String... arr) {
		for ( String s : arr ) {
			if ( s != null && !s.isBlank() )
				return s;
		}
		return null;
	}
}
