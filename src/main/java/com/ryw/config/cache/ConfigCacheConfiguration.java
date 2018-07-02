package com.ryw.config.cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationHome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.config.client.ConfigServicePropertySourceLocator;
import org.springframework.context.annotation.Bean;

public class ConfigCacheConfiguration {
	
	private static final Log logger = LogFactory.getLog(ConfigCacheConfiguration.class);
	
	@Value("${spring.cloud.config.localcache.path:}")
//	@Value("${citiccard.cloud.config.localcache.path:}")
	private String backupFilePath;

	@Value("${spring.cloud.config.localcache.save:true}")
//	@Value("${citiccard.cloud.config.localcache.save:true}")
	private boolean saveEnabled;
	
	@Value("${spring.cloud.config.localcache.load:false}")
//	@Value("${citiccard.cloud.config.localcache.load:false}")
	private boolean loadEnabled;
	
	String appPath = new ApplicationHome().getDir().getAbsolutePath();
	
	@Bean
	@ConditionalOnProperty(value = "spring.cloud.config.localcache.enabled", havingValue = "true", matchIfMissing = true)
	public ConfigCacheSouceLocator localConfigCache(ConfigServicePropertySourceLocator remoteSourceLocator) {
		if ("".equals(backupFilePath) || backupFilePath == null) {
			backupFilePath = appPath + "/local-config.properties";
			logger.info("backupFilePath is not null,and appPath is " + appPath);
		}
		return new ConfigCacheSouceLocator(remoteSourceLocator, backupFilePath, saveEnabled, loadEnabled);
	}

}
