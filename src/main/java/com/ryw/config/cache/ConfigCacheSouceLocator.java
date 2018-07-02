package com.ryw.config.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.cloud.config.client.ConfigServicePropertySourceLocator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;

import com.alibaba.fastjson.JSON;

@Order(Ordered.LOWEST_PRECEDENCE)
public class ConfigCacheSouceLocator implements PropertySourceLocator{
	
	private static final Log logger = LogFactory.getLog(ConfigCacheSouceLocator.class);
	
	private static final String REMOTE_CONFIG_BACKUP = "local-config";
	private static final String CONFIG_CLIENT_VERSION = "config.client.version";
	
	private ConfigServicePropertySourceLocator remoteSourceLocator;
	
	private String backupFilePath;
	private boolean saveEnabled = false;
	private boolean loadEnabled = false;
	
	
	public ConfigCacheSouceLocator(ConfigServicePropertySourceLocator remoteSourceLocator, String backupFilePath,
			boolean saveEnabled, boolean loadEnabled) {
		logger.info("[{com.ryw.config.cache.ConfigCacheSouceLocator.ConfigCacheSouceLocator()}], input: backupFilePath = " + 
			backupFilePath + ", saveEnabled = " + saveEnabled + ", loadEnabled = " + loadEnabled);
		this.remoteSourceLocator = remoteSourceLocator;
		this.backupFilePath = backupFilePath;
		this.saveEnabled = saveEnabled;
		this.loadEnabled = loadEnabled;
	}

	@Override
	public PropertySource<?> locate(Environment environment) {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.locate(), input:" + JSON.toJSONString(environment));
		PropertySource<?> ret = null;
		//判断是否成功从远程取到配置项
		PropertySource<?> remote = null;
		try {
			remote = remoteSourceLocator.locate(environment);
		} catch (Exception e) {
			logger.error("Failed to cache the remote configurations！", e);
		}
		
		//如果成功从远程获取到配置项，则与本地缓存版本号进行对比，如果不同，则替换本地缓存
		if (remote != null) {
			//获取本地备份文件
			Properties localCache;
			try {
				localCache = getLocalCache();
			} catch (IOException e) {
				return null;
			}
			
			//如果本地配置文件不存在，或者版本已更新，那么则保存到本地
			if (localCache == null || versionUpdateed(localCache, remote)) {
				saveToDisk(remote);
			}
			ret = null;
		}else {
			//远程读取失败，根据用户配置是否从本地读取缓存
			if (!loadEnabled) {
				return null;
			}
			logger.warn("Loading local cache");
			try {
				ret = getPropertySource(backupFilePath);
			} catch (Exception e) {
				logger.error("Failed to Load local cache!", e);
			}
		}
		
		return ret;
	}
	
	private Properties getLocalCache() throws IOException {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.getLocalCache()");
		try (FileInputStream is = new FileInputStream(backupFilePath)){
			Properties localCache = new Properties();
			localCache.load(is);
			return localCache;
		} catch (FileNotFoundException e) {
			// 本地缓存不存在，直接将远程配置保存到本地
			logger.warn(e);
			return null;
		}
	}
	 
	private boolean versionUpdateed(Properties localCache,PropertySource<?> remote) {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.versionUpdateed()");
		//本地缓存存在，则与远程版本号进行比较
		String localVersion = localCache.getProperty(CONFIG_CLIENT_VERSION);
		String remoteVersion = (String) remote.getProperty(CONFIG_CLIENT_VERSION);
		logger.info("LocalVersion:" + localVersion + ", RemoteVersion:" + remoteVersion);
		
		if (remoteVersion == null) {
			return true;
		}
		
		if (!remoteVersion.equals(localVersion)) {
			logger.info("Config versions are different! Remote: " + remoteVersion + ", local: " + localVersion 
					+ " Refresh the local configuration cache!");
			return true;
		}else {
			return false;
		}
	}
	
	private void saveToDisk(PropertySource<?> remoteConfig) {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.saveToDisk()");
		if (!saveEnabled) {
			logger.info("Properties for saving remote configuration is 'false'.");
			return;
		}
		
		if (!(remoteConfig instanceof EnumerablePropertySource)) {
			logger.info("Unable to cache remote configuration!");
			return;
		}
		
		int lastIndex = backupFilePath.lastIndexOf(".");
		String oldBackupFilePath = backupFilePath.substring(0,lastIndex) + "_old" + backupFilePath.substring(lastIndex);
		try {
			Properties prop = new Properties();
			EnumerablePropertySource<?> eps = (EnumerablePropertySource<?>) remoteConfig;
			String[] propertyNames = eps.getPropertyNames();
			for (String name : propertyNames) {
				//使用 property() 代替put
				prop.setProperty(name, eps.getProperty(name).toString());
			}
			
			File backupFile = new File(backupFilePath);
			File oldBackupFile = new File(oldBackupFilePath);
			
			if (backupFile.exists()) {
				if (oldBackupFile.exists()) {
					if (!oldBackupFile.delete()) {
						logger.warn("Delete " + oldBackupFilePath + "failed!");
					}
				}
				
				Path cacheOldProperty = Files.move(backupFile.toPath(), oldBackupFile.toPath());
				if (cacheOldProperty == null && !backupFile.delete()) {
					return;
				}
			}
			
			refreshLocalCache(remoteConfig, prop, oldBackupFile);
		} catch (IOException e) {
			logger.error("Failed to refresh local cache!", e);
		}
	}
	
	private void refreshLocalCache(PropertySource<?> remoteConfig, Properties prop, File backupFile) {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.refreshLocalCache()");
		String version = (String) remoteConfig.getProperty(CONFIG_CLIENT_VERSION);
		logger.info("Writing configurations to local cache file:" + backupFilePath);
		try(FileOutputStream os = new FileOutputStream(backupFilePath, true)){
			StringBuilder sb = new StringBuilder();
			sb.append("This is local file cache of properties at remote config server");
			sb.append("\n");
			sb.append("#Application can work with this local properties cache file when remote config servet is unavailable");
			prop.setProperty(CONFIG_CLIENT_VERSION, version == null ? "" : version);
			prop.store(os, sb.toString());
		}catch (Exception e) {
			logger.error("Failed to write local cache!", e);
		}
		logger.info("Successfully write configurations to local cache file.");
	}
	
	private PropertySource<?> getPropertySource(String filePath) throws IOException {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.getPropertySource()");
		Properties prop = loadPropertiesFromFile(filePath);
		if (prop.isEmpty()) {
			return null;
		}
		return new PropertiesPropertySource(REMOTE_CONFIG_BACKUP, prop);
	}
	
	private Properties loadPropertiesFromFile(String filePath) {
		logger.info("com.ryw.config.cache.ConfigCacheSouceLocator.loadPropertiesFromFile()");
		Properties prop = new Properties();
		try (InputStream is = new FileInputStream(filePath)){
			prop.load(is);
		} catch (IOException e) {
			logger.error("Unbale to load local cache!", e);
		}
		return prop;
	}
	

}
