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

package org.apache.roller.weblogger.ui.core;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import jakarta.servlet.ServletContext;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserCache;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.core.plugins.UIPluginManager;
import org.apache.roller.weblogger.ui.core.plugins.UIPluginManagerImpl;
import org.apache.velocity.runtime.RuntimeSingleton;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.web.context.support.WebApplicationContextUtils;


/**
 * Statics and helper methods for the Roller web application/context.
 *
 * <p>Startup/shutdown used to happen here too (this class extended
 * {@code ContextLoaderListener}/{@code ServletContextListener}); that role
 * has moved to {@code org.apache.roller.weblogger.boot.RollerLifecycle}, a
 * Spring Boot {@code SmartLifecycle} bean. This class now only holds the
 * statics that lifecycle populates ({@link #getServletContext()},
 * {@link #getPasswordEncoder()}) plus the helper methods the lifecycle calls
 * ({@link #initializeSecurityFeatures(ServletContext)},
 * {@link #setupVelocity()}) and the ones controllers/filters still call
 * directly ({@link #getUIPluginManager()}, {@link #flushAuthenticationUserCache(String)}).
 */
public class RollerContext {

    private static final Log log = LogFactory.getLog(RollerContext.class);

    private static ServletContext servletContext = null;
    private static DelegatingPasswordEncoder encoder;


    private RollerContext() {
        // static helpers only; RollerLifecycle owns the startup sequence now
    }


    /**
     * Called once by {@code RollerLifecycle.start()} to publish the
     * {@link ServletContext} that used to be captured in
     * {@code contextInitialized}.
     */
    public static void hold(ServletContext sc) {
        servletContext = sc;
    }


    /**
     * Access to the plugin manager for the UI layer. TODO: we may want
     * something similar to the Roller interface for the UI layer if we dont
     * want methods like this here in RollerContext.
     */
    public static UIPluginManager getUIPluginManager() {
        return UIPluginManagerImpl.getInstance();
    }


    /**
     * Get the ServletContext.
     *
     * @return ServletContext
     */
    public static ServletContext getServletContext() {
        return servletContext;
    }

    public static PasswordEncoder getPasswordEncoder() {
        return encoder;
    }

    /**
     * Initialize the Velocity rendering engine.
     *
     * <p>Called by {@code RollerLifecycle.start()}, in the same spot in the
     * startup sequence that {@code contextInitialized} used to call it from.
     */
    public static void setupVelocity() throws WebloggerException {
        log.info("Initializing Velocity");

        // initialize the Velocity engine
        Properties velocityProps = new Properties();

        try {
            try (InputStream instream = servletContext.getResourceAsStream("/WEB-INF/velocity.properties")) {
                velocityProps.load(instream);
            }
            if (log.isDebugEnabled()) {
                log.debug("Velocity props = " + velocityProps);
            }

            // init velocity
            RuntimeSingleton.init(velocityProps);

        } catch (Exception e) {
            throw new WebloggerException(e);
        }

    }

    /**
     * Setup Spring Security security features.
     *
     * <p>Called by {@code RollerLifecycle.start()}. Looks up beans by the
     * internal names the {@code security.xml} namespace parser assigns them
     * (e.g. {@code org.springframework.security.authenticationManager});
     * that XML file is imported into the application context starting Task
     * 3 of the Boot conversion, and Task 4 replaces this by-name lookup with
     * proper {@code @Bean} wiring in {@code SecurityConfig}.
     */
    public static void initializeSecurityFeatures(ServletContext context) {

        ApplicationContext ctx =
                WebApplicationContextUtils.getRequiredWebApplicationContext(context);

        String rememberMe = WebloggerConfig.getProperty("rememberme.enabled");
        boolean rememberMeEnabled = Boolean.valueOf(rememberMe);
        log.info("Remember Me enabled: " + rememberMeEnabled);
        context.setAttribute("rememberMeEnabled", rememberMe);

        if (!rememberMeEnabled) {
            ProviderManager provider =
                (ProviderManager) ctx.getBean("org.springframework.security.authenticationManager");
            for (AuthenticationProvider authProvider : provider.getProviders()) {
                if (authProvider instanceof RememberMeAuthenticationProvider) {
                    provider.getProviders().remove(authProvider);
                }
            }
        }

        encoder = createPasswordEncoder();

        String daoBeanName = "org.springframework.security.authentication.dao.DaoAuthenticationProvider#0";

        // for LDAP-only authentication, no daoBeanName (i.e., UserDetailsService) may be provided in security.xml.
        if (ctx.containsBean(daoBeanName)) {
            DaoAuthenticationProvider provider = (DaoAuthenticationProvider) ctx.getBean(daoBeanName);
            provider.setPasswordEncoder(encoder);
        }

        if (WebloggerConfig.getBooleanProperty("securelogin.enabled")) {
            LoginUrlAuthenticationEntryPoint entryPoint =
                    (LoginUrlAuthenticationEntryPoint) ctx.getBean("_formLoginEntryPoint");
            entryPoint.setForceHttps(true);
        }
    }

    @SuppressWarnings("deprecation")
    private static DelegatingPasswordEncoder createPasswordEncoder() {

        Map<String, PasswordEncoder> encoders = new HashMap<>();

        // outdated digest encoder used for lazy upgrades from pws encoded by old roller versions.
        String migrateFrom = WebloggerConfig.getProperty("passwds.encryption.lazyUpgradeFrom");
        
        if(migrateFrom == null || migrateFrom.isEmpty()) {
            log.debug("lazy pw upgrade disabled");
        } else if (migrateFrom.equals("plaintext")) {
            encoders.put(null, org.springframework.security.crypto.password.NoOpPasswordEncoder.getInstance());
        } else if (migrateFrom.equals("MD5")) {
            encoders.put(null, new org.springframework.security.crypto.password.MessageDigestPasswordEncoder("MD5"));
        } else if (migrateFrom.equals("SHA")) {
            encoders.put(null, new org.springframework.security.crypto.password.MessageDigestPasswordEncoder("SHA-1"));
        } else {
            throw new RuntimeException("passwds.encryption.lazyUpgradeFrom="+migrateFrom+" is no valid encoding to upgrade from.");
        }
        
        // supported encoders
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        encoders.put("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        // provided by bouncy castle dependency
        encoders.put("scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        // just for testing
        encoders.put("noop", org.springframework.security.crypto.password.NoOpPasswordEncoder.getInstance());
        
        String algorithm = WebloggerConfig.getProperty("passwds.encryption.algorithm");
        
        if (WebloggerConfig.getBooleanProperty("passwds.encryption.enabled")) {
            
            if ("SHA".equals(algorithm) || "MD5".equals(algorithm)) {
                throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is outdated,"
                        + " please set passwds.encryption.algorithm to 'bcrypt' for automatic lazy upgrade.");
            }
            
            if (!encoders.containsKey(algorithm)) {
                throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is not supported.");
            }
        } else {
            log.warn("New passwords are stored in plain text!");
            algorithm = "noop";
        }
        
        log.info("Password Encryption Algorithm set to '" + algorithm + "'");
        
        return new DelegatingPasswordEncoder(algorithm, encoders);
    }


    /**
     * Flush user from any caches maintained by security system.
     */
    public static void flushAuthenticationUserCache(String userName) {
        ApplicationContext ctx =
                WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
        try {
            UserCache userCache = (UserCache) ctx.getBean("userCache");
            if (userCache != null) {
                userCache.removeUserFromCache(userName);
            }
        } catch (NoSuchBeanDefinitionException exc) {
            log.debug("No userCache bean in context", exc);
        }
    }


}
