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
package org.apache.roller.weblogger.ui.rendering.velocity;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.business.themes.ThemeNotFoundException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.util.ExtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ThemeResourceLoader}, focused on the two places a lookup
 * failure is re-thrown as Velocity's {@link ResourceNotFoundException}. Both
 * are already logged with the original exception, but the rethrow used to
 * discard it anyway (see CLAUDE.md's PreserveStackTrace note) -- these pin
 * that the cause now survives alongside the message.
 */
class ThemeResourceLoaderTest {

    private final ThemeResourceLoader loader = new ThemeResourceLoader();
    private ThemeManager themeManager;

    @BeforeEach
    void setUp() {
        themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);

        // ResourceLoader.log and rsvc (inherited from Velocity) are only set
        // by commonInit(), which the real engine calls during startup; this
        // test never starts the engine, so it calls both directly. The facade
        // arrives the way RollerVelocity.initialize hands it over: as the
        // engine's application attribute, read in init().
        RuntimeServices rsvc = mock(RuntimeServices.class);
        when(rsvc.getLog(anyString())).thenReturn(LoggerFactory.getLogger("test"));
        when(rsvc.getApplicationAttribute(RollerVelocity.WEBLOGGER_ATTRIBUTE)).thenReturn(weblogger);
        loader.commonInit(rsvc, new ExtProperties());
        loader.init(new ExtProperties());
    }

    @Test
    void aThemeNotFoundFailureIsWrappedWithItsCause() throws WebloggerException {
        ThemeNotFoundException cause = new ThemeNotFoundException("no such theme");
        when(themeManager.getTheme("missingtheme")).thenThrow(cause);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("missingtheme:sometemplate", "UTF-8"));

        assertEquals(cause, thrown.getCause());
    }

    @Test
    void aGenericLookupFailureIsWrappedWithItsCause() throws WebloggerException {
        WebloggerException cause = new WebloggerException("database unavailable");
        when(themeManager.getTheme("brokentheme")).thenThrow(cause);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("brokentheme:sometemplate", "UTF-8"));

        assertEquals(cause, thrown.getCause());
    }
}
