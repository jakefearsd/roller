/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.PropertyExpander;


/**
 * This is the single entry point for accessing configuration properties in Roller.
 */
public final class WebloggerConfig {
    
    private final static String default_config = "/org/apache/roller/weblogger/config/roller.properties";
    private final static String custom_config = "/roller-custom.properties";
    private final static String junit_config = "/roller-junit.properties";
    private final static String custom_jvm_param = "roller.custom.config";

    private final static String env_prefix = "ROLLER_";

    private final static Pattern SECRET_KEY =
            Pattern.compile("password|token|secret", Pattern.CASE_INSENSITIVE);

    private final static Properties config;

    private final static Log log;
    

    /*
     * Static block run once at class loading
     *
     * We load the default properties and any custom properties we find
     */
    static {
        config = new Properties();

        try {
            // we'll need this to get at our properties files in the classpath
            Class<?> configClass = Class.forName("org.apache.roller.weblogger.config.WebloggerConfig");

            // first, lets load our default properties
            try (InputStream is = configClass.getResourceAsStream(default_config)) {
                config.load(is);
            }
            
            // first, see if we can find our junit testing config
            try (InputStream test = configClass.getResourceAsStream(junit_config)) {
                
                if (test != null) {

                    config.load(test);
                    System.out.println("Roller Weblogger: Successfully loaded junit properties file from classpath");
                    System.out.println("File path : " + configClass.getResource(junit_config).getFile());

                } else {

                    // now, see if we can find our custom config
                    try(InputStream custom = configClass.getResourceAsStream(custom_config)) {

                        if (custom != null) {
                            config.load(custom);
                            System.out.println("Roller Weblogger: Successfully loaded custom properties file from classpath");
                            System.out.println("File path : " + configClass.getResource(custom_config).getFile());
                        } else {
                            System.out.println("Roller Weblogger: No custom properties file found in classpath");
                        }

                        System.out.println("(To run eclipse junit local tests see docs/testing/roller-junit.properties)");
                    }
                }
            }

            // finally, check for an external config file
            String env_file = System.getProperty(custom_jvm_param);
            if(env_file != null && env_file.length() > 0) {
                File custom_config_file = new File(env_file);

                // make sure the file exists, then try and load it
                if(custom_config_file.exists()) {
                    try(InputStream is = new FileInputStream(custom_config_file)) {
                        config.load(is);
                    }
                    System.out.println("Roller Weblogger: Successfully loaded custom properties from "+
                            custom_config_file.getAbsolutePath());
                } else {
                    System.out.println("Roller Weblogger: Failed to load custom properties from "+
                            custom_config_file.getAbsolutePath());
                }

            }

            // Highest-precedence layer: ROLLER_* environment variables. Placed
            // before property expansion below so an env-supplied value can
            // still carry ${...} references.
            applyEnvironmentOverrides(config, System.getenv());

            // Now expand system properties for properties in the config.expandedProperties list,
            // replacing them by their expanded values.
            String expandedPropertiesDef = config.getProperty("config.expandedProperties");
            if (expandedPropertiesDef != null) {
                String[] expandedProperties = expandedPropertiesDef.split(",");
                for (int i = 0; i < expandedProperties.length; i++) {
                    String propName = expandedProperties[i].trim();
                    String initialValue = config.getProperty(propName);
                    if (initialValue != null) {
                        String expandedValue = PropertyExpander.expandSystemProperties(initialValue);
                        config.setProperty(propName, expandedValue);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            // A RuntimeException here is a programming or configuration
            // error, not a missing optional file -- do not boot on it.
            if (e instanceof RuntimeException re) {
                throw re;
            }
        }

        // tell log4j2 to use the optionally specified config file instead of Roller's,
        // but only if it hasn't already been set with -D at JVM startup.
        String log4j2ConfigKey = "log4j.configurationFile";
        String customLog4j2File = config.getProperty(log4j2ConfigKey);
        if(customLog4j2File != null && !customLog4j2File.isBlank() && System.getProperty(log4j2ConfigKey) == null) {
            System.setProperty(log4j2ConfigKey, customLog4j2File);
        }
        
        // this bridges java.util.logging -> SLF4J which ends up being log4j2, probably.
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        
        // finally we can start logging...
        log = LogFactory.getLog(WebloggerConfig.class);

        // some debugging for those that want it
        if(log.isDebugEnabled()) {
            log.debug("WebloggerConfig looks like this ...");

            String key;
            Enumeration<Object> keys = config.keys();
            while(keys.hasMoreElements()) {
                key = (String) keys.nextElement();
                log.debug(key+"="+maskSecret(key, config.getProperty(key)));
            }
        }

    }


    // no, you may not instantiate this class :p
    private WebloggerConfig() {}

    /**
     * Overlay {@code ROLLER_*} environment variables onto the loaded
     * configuration. This is the highest-precedence layer, which is what lets
     * the production image take its whole configuration from the deploy host's
     * .env with no properties file mounted (see
     * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
     *
     * <p>The name mapping strips the prefix, lowercases, and turns {@code _}
     * into {@code .}. A derived name that matches an existing key
     * case-insensitively is written to that key's ORIGINAL spelling, so
     * {@code ROLLER_DATABASE_JDBC_DRIVERCLASS} reaches
     * {@code database.jdbc.driverClass} instead of quietly creating a
     * lowercase twin nothing reads. A derived name matching nothing is used
     * as-is, which is required rather than incidental: {@code mail.port} has no
     * entry in roller.properties and {@code uploads.dir} is commented out, so
     * an allowlist of keys with active defaults could set neither.
     *
     * <p>Package-private and taking an explicit map so it can be tested;
     * {@code System.getenv()} is unmodifiable and the caller below is a static
     * initializer that runs once per JVM.
     */
    static void applyEnvironmentOverrides(Properties config, Map<String, String> env) {

        Map<String, String> knownByLowerCase = new HashMap<>();
        for (String key : config.stringPropertyNames()) {
            String previous = knownByLowerCase.put(key.toLowerCase(Locale.ROOT), key);
            if (previous != null && !previous.equals(key)) {
                throw new IllegalStateException("Configuration keys '" + previous + "' and '"
                        + key + "' differ only in case, so an environment override cannot "
                        + "address them unambiguously.");
            }
        }

        // Sorted for deterministic behaviour when two variables derive the same key.
        for (Map.Entry<String, String> entry : new TreeMap<>(env).entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(env_prefix) || name.length() == env_prefix.length()) {
                continue;
            }
            String derived = name.substring(env_prefix.length())
                                 .toLowerCase(Locale.ROOT)
                                 .replace('_', '.');
            config.setProperty(knownByLowerCase.getOrDefault(derived, derived), entry.getValue());
        }
    }

    /**
     * Replace a secret's value with a fixed mask for logging. The debug dump
     * below prints every key and value, and the environment overlay makes
     * turning that on while diagnosing a configuration problem much more
     * likely.
     */
    static String maskSecret(String key, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_KEY.matcher(key).find() ? "********" : value;
    }

    /**
     * Loads Roller's configuration.
     * Call this as early as possible in the lifecycle.
     */
    public static void init() {
        // triggers static block
    }
    
    /**
     * Retrieve a property value
     * @param     key Name of the property
     * @return    String Value of property requested, null if not found
     */
    public static String getProperty(String key) {
        String value = config.getProperty(key);
        log.debug("Fetching property ["+key+"="+value+"]");
        return value == null ? null : value.trim();
    }
    
    /**
     * Retrieve a property value
     * @param     key Name of the property
     * @param     defaultValue Default value of property if not found     
     * @return    String Value of property requested or defaultValue
     */
    public static String getProperty(String key, String defaultValue) {
        String value = config.getProperty(key);
        log.debug("Fetching property ["+key+"="+value+",defaultValue="+defaultValue+"]");
        if (value == null) {
            return defaultValue;
        }
        
        return value.trim();
    }

    /**
     * Retrieve a property as a boolean ... defaults to false if not present.
     */
    public static boolean getBooleanProperty(String name) {
        return getBooleanProperty(name,false);
    }

    /**
     * Retrieve a property as a boolean ... with specified default if not present.
     */
    public static boolean getBooleanProperty(String name, boolean defaultValue) {
        // get the value first, then convert
        String value = WebloggerConfig.getProperty(name);

        if(value == null) {
            return defaultValue;
        }

        return Boolean.valueOf(value);
    }

    /**
     * Retrieve a property as an int ... defaults to 0 if not present.
     */
    public static int getIntProperty(String name) {
        return getIntProperty(name, 0);
    }

    /**
     * Retrieve a property as a int ... with specified default if not present.
     */
    public static int getIntProperty(String name, int defaultValue) {
        // get the value first, then convert
        String value = WebloggerConfig.getProperty(name);

        if (value == null) {
            return defaultValue;
        }

        return Integer.valueOf(value);
    }

    /**
     * Retrieve all property keys
     * @return Enumeration A list of all keys
     **/
    public static Enumeration<Object> keys() {
        return config.keys();
    } 

    /**
     * Set the "uploads.dir" property at runtime.
     * <p />
     * Properties are meant to be read-only, but we make this exception because  
     * we know that some people are still writing their uploads to the webapp  
     * context and we can only get that path at runtime (and for unit testing).
     * <p />
     * This property is *not* persisted in any way.
     */
    public static void setUploadsDir(String path) {
        // only do this if the user wants to use the webapp context
        if("${webapp.context}".equals(config.getProperty("uploads.dir"))) {
            config.setProperty("uploads.dir", path);
        }
    }
    
    /**
     * Set the "themes.dir" property at runtime.
     * <p />
     * Properties are meant to be read-only, but we make this exception because  
     * we know that some people are still using their themes in the webapp  
     * context and we can only get that path at runtime (and for unit testing).
     * <p />
     * This property is *not* persisted in any way.
     */
    public static void setThemesDir(String path) {
        // only do this if the user wants to use the webapp context
        if("${webapp.context}".equals(config.getProperty("themes.dir"))) {
            config.setProperty("themes.dir", path);
        }
    }

    /**
     * Return the value of the authentication.method property as an AuthMethod
     * enum value.  Matching is done by checking the propertyName of each AuthMethod
     * enum object.
     * <p />
     * @throws IllegalArgumentException if property value defined in the properties
     * file is missing or not the property name of any AuthMethod enum object.
     */
    public static AuthMethod getAuthMethod() {
        return AuthMethod.getAuthMethod(getProperty("authentication.method"));
    }
    
}
