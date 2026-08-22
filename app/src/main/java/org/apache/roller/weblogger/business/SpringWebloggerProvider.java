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

import org.apache.roller.weblogger.business.jpa.WebloggerBeanConfig;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

/**
 * A Spring specific implementation of a WebloggerProvider, and the bean
 * that answers "is the business tier up?" for everything that used to ask
 * {@code WebloggerFactory}.
 *
 * <p>Bootstrapping builds (or, for the {@link ApplicationContext} constructor,
 * reuses) a Spring context and hands back its {@link Weblogger} bean. It owns
 * the whole startup sequence the two bootstrap sites ({@code RollerLifecycle}
 * and {@code InstallController}) used to spell out by hand: the
 * prepare-before-construct guard, obtaining the bean, {@code initialize()},
 * and {@code release()} of the bootstrapping thread's persistence session.
 *
 * <p>Production gets the {@code @Component} (constructed with the Boot
 * context, into which {@link WebloggerBeanConfig} is already component-
 * scanned). {@code TestUtils} uses {@link #standalone()}, which builds a
 * private context from {@link WebloggerBeanConfig} on first bootstrap.
 */
@Component
public class SpringWebloggerProvider implements WebloggerProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringWebloggerProvider.class);

    private final ApplicationContext existingContext;

    // Deliberately left null by both constructors -- see standalone()'s
    // javadoc. bootstrap() must run after WebloggerStartup.prepare(), and
    // only the caller's ordering guarantees that; building the context in the
    // constructor would risk running before prepare() and is the specific
    // mistake this design avoids.
    private ApplicationContext context;

    private volatile Weblogger weblogger;

    /**
     * Instantiate a provider around an already-built application context
     * (the webapp's Spring context, which already imports
     * {@link WebloggerBeanConfig}). This is the injection constructor.
     *
     * @param existingContext the application context to source the Weblogger bean from.
     */
    @Autowired
    public SpringWebloggerProvider(ApplicationContext existingContext) {
        this.existingContext = existingContext;
    }

    private SpringWebloggerProvider() {
        this.existingContext = null;
    }

    /**
     * A provider that builds its own application context from
     * {@link WebloggerBeanConfig} -- the test suite's path.
     *
     * <p>The context is not built here: {@link WebloggerStartup#prepare()}
     * must run first (it prepares the {@code DatabaseProvider} that
     * {@code WebloggerBeanConfig} reads), and that ordering is only
     * guaranteed by the caller invoking {@link #bootstrap()} afterward.
     */
    public static SpringWebloggerProvider standalone() {
        return new SpringWebloggerProvider();
    }

    @Override
    public boolean isBootstrapped() {
        return weblogger != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent: a repeat call on the same instance neither builds a
     * second (leaked) self-owned context nor initializes the tier again.
     *
     * <p>The tier counts as bootstrapped from the moment the Weblogger bean
     * has been obtained -- before {@code initialize()} runs, and even if
     * {@code initialize()} then fails. That is the shape the two bootstrap
     * sites always had (the provider was installed, then initialized), so
     * {@code BootstrapFilter} lets requests through in both cases; an
     * {@code initialize()} failure surfaces as a {@link BootstrapException}
     * for the caller to log.
     */
    @Override
    public synchronized void bootstrap() throws BootstrapException {
        if (weblogger != null) {
            return;
        }

        // if the app hasn't been properly started so far then bail
        if (!WebloggerStartup.isPrepared()) {
            throw new IllegalStateException("Cannot bootstrap until application has been properly prepared");
        }

        log.info("Bootstrapping Roller Weblogger business tier");

        if (this.context == null) {
            this.context = (existingContext != null)
                    ? existingContext
                    : new AnnotationConfigApplicationContext(WebloggerBeanConfig.class);
        }

        Weblogger built = context.getBean(Weblogger.class);
        if (built == null) {
            throw new BootstrapException("Bootstrapping failed, Weblogger instance is null");
        }
        this.weblogger = built;

        // TRANSITIONAL -- deleted by Task 20 of the 2026-08-22 plan along with
        // WebloggerFactory itself: the not-yet-migrated static callers must
        // keep seeing the tier this bean bootstrapped.
        WebloggerFactory.installProvider(this);

        log.info("Roller Weblogger business tier successfully bootstrapped");
        log.info("   Version: {}", built.getVersion());
        log.info("   Revision: {}", built.getRevision());

        try {
            built.initialize();
        } catch (InitializationException ex) {
            throw new BootstrapException("Roller Weblogger initialization failed", ex);
        } finally {
            built.release();
        }
    }

    @Override
    public Weblogger getWeblogger() {
        Weblogger current = weblogger;
        if (current == null) {
            throw new IllegalStateException("Roller Weblogger has not been bootstrapped yet");
        }
        return current;
    }

}
