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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpringWebloggerProviderTest {

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
}
