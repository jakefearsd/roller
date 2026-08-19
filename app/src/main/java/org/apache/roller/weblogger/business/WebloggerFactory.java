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

package org.apache.roller.weblogger.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;


/**
 * Provides access to the Weblogger instance and bootstraps the business tier.
 */
public final class WebloggerFactory {
    
    private static final Logger log = LoggerFactory.getLogger(WebloggerFactory.class);
    
    // our configured weblogger provider
    private static WebloggerProvider webloggerProvider = null;

    // non-instantiable
    private WebloggerFactory() {
        // hello all you beautiful people
    }

    /**
     * True if bootstrap process has been completed, False otherwise.
     */
    public static boolean isBootstrapped() {
        return webloggerProvider != null;
    }


    /**
     * Test seam: install a ready-made provider, skipping the startup sequence.
     *
     * <p>{@link #bootstrap(WebloggerProvider)} refuses to run until
     * {@link org.apache.roller.weblogger.business.startup.WebloggerStartup#isPrepared()},
     * which needs a real database. That check exists to stop a half-started
     * application serving requests, but it is redundant when the caller supplies a
     * fully-formed provider, and it is what made the 179 static
     * {@code WebloggerFactory.getWeblogger()} call sites in the controllers
     * untestable without a container.
     *
     * <p>Package-private on purpose: only test support in this package can reach it,
     * so it cannot become a production back door. Pass {@code null} to reset.
     */
    static void installProvider(WebloggerProvider provider) {
        webloggerProvider = provider;
    }


    /** Test seam companion: the provider currently installed, or null. */
    static WebloggerProvider currentProvider() {
        return webloggerProvider;
    }
    
    
    /**
     * Accessor to the Weblogger Weblogger business tier.
     * 
     * @return Weblogger An instance of Weblogger.
     * @throws IllegalStateException If the app has not been properly bootstrapped yet.
     */
    public static Weblogger getWeblogger() {
        if (webloggerProvider == null) {
            throw new IllegalStateException("Roller Weblogger has not been bootstrapped yet");
        }
        
        return webloggerProvider.getWeblogger();
    }
    
    
    /**
     * Bootstrap the Roller Weblogger business tier, uses specified WebloggerProvider.
     *
     * Bootstrapping the application effectively instantiates all the necessary
     * pieces of the business tier and wires them together so that the app is 
     * ready to run.
     *
     * @param provider A WebloggerProvider to use for bootstrapping.
     * @throws IllegalStateException If the app has not been properly prepared yet.
     * @throws BootstrapException If an error happens during the bootstrap process.
     */
    public static void bootstrap(WebloggerProvider provider)
            throws BootstrapException {
        
        // if the app hasn't been properly started so far then bail
        if (!WebloggerStartup.isPrepared()) {
            throw new IllegalStateException("Cannot bootstrap until application has been properly prepared");
        }
        
        if (provider == null) {
            throw new NullPointerException("WebloggerProvider is null");
        }
        
        log.info("Bootstrapping Roller Weblogger business tier");

        log.info("Weblogger Provider = {}", provider.getClass().getName());
        
        // save reference to provider
        webloggerProvider = provider;
        
        // bootstrap weblogger provider
        webloggerProvider.bootstrap();
        
        // make sure we are all set
        if(webloggerProvider.getWeblogger() == null) {
            throw new BootstrapException("Bootstrapping failed, Weblogger instance is null");
        }
        
        log.info("Roller Weblogger business tier successfully bootstrapped");
        log.info("   Version: {}", webloggerProvider.getWeblogger().getVersion());
        log.info("   Revision: {}", webloggerProvider.getWeblogger().getRevision());
    }
    
}
