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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * A Spring specific implementation of a WebloggerProvider.
 *
 * <p>Mirrors {@link GuiceWebloggerProvider}: bootstrapping builds (or, for the
 * two-arg constructor, reuses) a Spring {@link ApplicationContext} and hands
 * back its {@link Weblogger} bean.
 */
public class SpringWebloggerProvider implements WebloggerProvider {

    private final ApplicationContext existingContext;

    private ApplicationContext context;

    /**
     * Instantiate a new SpringWebloggerProvider that builds its own
     * application context from {@link WebloggerBeanConfig}.
     *
     * <p>The context is not built here: {@link org.apache.roller.weblogger.business.startup.WebloggerStartup#prepare()}
     * must run first (it prepares the {@code DatabaseProvider} that
     * {@code WebloggerBeanConfig} reads), and that ordering is only
     * guaranteed by the caller invoking {@link #bootstrap()} afterward.
     * Building the context here in the constructor would risk running before
     * {@code prepare()}.
     */
    public SpringWebloggerProvider() {
        this.existingContext = null;
    }

    /**
     * Instantiate a new SpringWebloggerProvider around an already-built
     * application context (the webapp's Spring context, which already
     * imports {@link WebloggerBeanConfig}).
     *
     * @param existingContext the application context to source the Weblogger bean from.
     */
    public SpringWebloggerProvider(ApplicationContext existingContext) {
        this.existingContext = existingContext;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void bootstrap() {
        // Idempotent: a repeat bootstrap() call on the same instance (e.g. a
        // second WebloggerFactory.bootstrap() in the same JVM) must not build
        // a second self-owned context -- that would leak the first one, since
        // nothing else holds a reference to close it.
        if (this.context != null) {
            return;
        }
        this.context = (existingContext != null)
                ? existingContext
                : new AnnotationConfigApplicationContext(WebloggerBeanConfig.class);
    }

    /**
     * @inheritDoc
     */
    @Override
    public Weblogger getWeblogger() {
        return context.getBean(Weblogger.class);
    }

}
