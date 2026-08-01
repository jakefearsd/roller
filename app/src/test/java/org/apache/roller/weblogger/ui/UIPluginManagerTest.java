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

package org.apache.roller.weblogger.ui;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.plugins.UIPluginManager;
import org.apache.roller.weblogger.ui.core.plugins.WeblogEntryEditor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers editor registration and, in particular, that the editor named by
 * {@code plugins.defaultEditor} actually resolves.
 *
 * <p>It did not. {@code Summernote.getId()} returned {@code editor-xinha.jsp},
 * left over from the editor it replaced, while {@code roller.properties} names
 * {@code editor-summernote.jsp}. The lookup missed, {@code UIPluginManagerImpl}
 * logged "Default editor was not properly configured" and fell back to
 * {@code editors.values().iterator().next()} - the first entry of a HashMap.
 * Which editor an author got was therefore arbitrary, and frequently the plain
 * textarea instead of the rich one.
 *
 * <p>The previous version of this test asserted the default *was* the text
 * editor, which pinned the symptom rather than the intent.
 */
public class UIPluginManagerTest {

    @Test
    public void everyConfiguredEditorIsRegisteredUnderItsOwnId() {
        UIPluginManager pmgr = RollerContext.getUIPluginManager();

        assertEquals(2, pmgr.getWeblogEntryEditors().size(),
                "plugins.weblogEntryEditors lists two editors; if that changed, update this "
                        + "test deliberately rather than loosening it");

        for (WeblogEntryEditor editor : pmgr.getWeblogEntryEditors()) {
            assertEquals(editor.getId(), pmgr.getWeblogEntryEditor(editor.getId()).getId(),
                    "an editor must be retrievable by the id it reports. When getId() and the "
                            + "registry key disagree, lookups fall through to the default and "
                            + "the author gets an editor nobody chose.");
        }
    }

    @Test
    public void theConfiguredDefaultEditorResolves() {
        UIPluginManager pmgr = RollerContext.getUIPluginManager();

        String configuredDefault = WebloggerConfig.getProperty("plugins.defaultEditor");
        assertNotNull(configuredDefault, "plugins.defaultEditor must be set in roller.properties");

        WeblogEntryEditor fallback = pmgr.getWeblogEntryEditor(null);
        assertNotNull(fallback, "an unknown editor id must still yield an editor");

        assertEquals(configuredDefault, fallback.getId(),
                "the editor used when none is chosen must be the one named by "
                        + "plugins.defaultEditor. If these differ, UIPluginManagerImpl is falling "
                        + "back to an arbitrary HashMap entry and the default is effectively "
                        + "random.");
    }

    @Test
    public void aStaleEditorIdFallsBackToTheDefaultRatherThanFailing() {
        UIPluginManager pmgr = RollerContext.getUIPluginManager();

        // Weblogs store their chosen editor by id, so an id from an older
        // release must degrade to the default rather than break the edit page.
        WeblogEntryEditor editor = pmgr.getWeblogEntryEditor("editor-xinha.jsp");

        assertNotNull(editor, "a stale editor id must not produce a null editor");
        assertEquals(WebloggerConfig.getProperty("plugins.defaultEditor"), editor.getId(),
                "a stale editor id must fall back to the configured default");
    }
}
