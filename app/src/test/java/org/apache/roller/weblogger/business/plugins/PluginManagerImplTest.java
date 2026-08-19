/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.business.plugins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code plugins.page} is unset in the test configuration (the registry is
 * presently empty in production too -- see CLAUDE.md's Plugin System
 * section), so {@link PluginManagerImpl#hasPagePlugins()} reports none
 * registered. {@code mPagePlugins} is a static final field initialized
 * inline and never reassigned, so it can never be null -- this pins that
 * {@code hasPagePlugins()} answers from the collection's emptiness alone.
 */
class PluginManagerImplTest {

    @Test
    void hasPagePluginsIsFalseWhenNoneAreRegistered() {
        PluginManagerImpl mgr = new PluginManagerImpl();
        assertFalse(mgr.hasPagePlugins());
    }
}
