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

import org.apache.roller.testing.RollerDatabaseExtension;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringWebloggerProviderTest {

    @Test
    void bootstrapReusesAnExistingApplicationContextInsteadOfBuildingOne() {
        // The two-arg constructor exists precisely so the webapp's root Spring
        // context (which already imports WebloggerBeanConfig) is reused rather
        // than a second, independent context being built and left orphaned.
        Weblogger fakeWeblogger = mock(Weblogger.class);
        ApplicationContext existingContext = mock(ApplicationContext.class);
        when(existingContext.getBean(Weblogger.class)).thenReturn(fakeWeblogger);

        SpringWebloggerProvider provider = new SpringWebloggerProvider(existingContext);
        provider.bootstrap();

        assertSame(fakeWeblogger, provider.getWeblogger(),
                "bootstrap() must resolve the Weblogger bean from the supplied context, "
                        + "not build a new self-owned one");
    }

    @Test
    void bootstrapBuildsTheFullGraphWithSingletons() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        SpringWebloggerProvider provider = new SpringWebloggerProvider();
        provider.bootstrap();
        Weblogger weblogger = provider.getWeblogger();

        assertNotNull(weblogger.getWeblogManager());
        assertNotNull(weblogger.getWeblogEntryManager());
        assertNotNull(weblogger.getUserManager());
        assertNotNull(weblogger.getMediaFileManager());
        assertNotNull(weblogger.getIndexManager());
        assertNotNull(weblogger.getThemeManager());
        assertNotNull(weblogger.getPluginManager());
        assertNotNull(weblogger.getThreadManager());
        assertNotNull(weblogger.getPropertiesManager());
        assertNotNull(weblogger.getUrlStrategy());
        // the circular edge: the manager's Weblogger proxy must resolve back
        // to the same singleton graph (same manager instance both ways)
        assertSame(weblogger.getWeblogManager(), provider.getWeblogger().getWeblogManager());
    }

    @Test
    void repeatBootstrapIsIdempotent() throws Exception {
        RollerDatabaseExtension.ensureSchema();
        if (!WebloggerStartup.isPrepared()) {
            WebloggerStartup.prepare();
        }

        SpringWebloggerProvider provider = new SpringWebloggerProvider();
        provider.bootstrap();
        Weblogger first = provider.getWeblogger();

        // a second bootstrap() call must not build a second (leaked) context
        provider.bootstrap();
        Weblogger second = provider.getWeblogger();

        assertSame(first, second);
    }
}
