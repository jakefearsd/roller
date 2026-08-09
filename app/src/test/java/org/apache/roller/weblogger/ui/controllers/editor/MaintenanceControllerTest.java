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
package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.roller.weblogger.WebloggerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MaintenanceController}.
 *
 * <p>Each operation here (flush the page cache, rebuild the search index,
 * regenerate renditions) is destructive or expensive enough that the admin
 * only reaches for it occasionally, so a silent failure that still shows a success message
 * would go unnoticed for a long time. The interesting behaviour is therefore
 * just that: success is only reported when the manager call actually
 * succeeded, and a failure short-circuits before the confirmation.
 */
class MaintenanceControllerTest extends EditorControllerTestSupport {

    private MaintenanceController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new MaintenanceController());
        model = newModel();
    }

    @Test
    void executeJustRendersThePage() {
        String view = controller.execute(request, model);

        assertEquals(".Maintenance", view);
    }

    @Test
    void flushingTheCacheSavesTheWeblogAndConfirms() throws Exception {
        String view = controller.flushCache(request, model);

        assertEquals(".Maintenance", view);
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0, "A cache flush must be committed");
        assertTrue(messages(model).contains("maintenance.message.flushed"),
                "Expected a flush confirmation, got: " + messages(model));
    }

    @Test
    void aFailedCacheFlushIsReportedRatherThanConfirmed() throws Exception {
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveWeblog(any());

        String view = controller.flushCache(request, model);

        assertEquals(".Maintenance", view);
        assertTrue(errors(model).contains("Error flushing page cache"),
                "Expected a flush error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed flush must not also report success");
    }

    @Test
    void rebuildingTheIndexConfirms() throws Exception {
        String view = controller.index(request, model);

        assertEquals(".Maintenance", view);
        verify(weblogger.getIndexManager()).rebuildWeblogIndex(weblog);
        assertTrue(messages(model).contains("maintenance.message.indexed"),
                "Expected an index confirmation, got: " + messages(model));
    }

    @Test
    void aFailedIndexRebuildIsReported() throws Exception {
        doThrow(new WebloggerException("index corrupt"))
                .when(weblogger.getIndexManager()).rebuildWeblogIndex(any());

        String view = controller.index(request, model);

        assertEquals(".Maintenance", view);
        assertTrue(errors(model).contains("maintenance.message.indexed.failure"),
                "Expected an index-failure error, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed rebuild must not also report success");
    }

    @Test
    void regeneratingRenditionsConfirmsWithTheCount() throws Exception {
        when(weblogger.getMediaFileManager().regenerateRenditions(weblog)).thenReturn(3);
        registerMessage("maintenance.message.renditionsRegenerated", "Regenerated {0} image(s)");

        String view = controller.regenerateRenditions(request, model);

        assertEquals(".Maintenance", view);
        verify(weblogger.getMediaFileManager()).regenerateRenditions(weblog);
        assertTrue(messages(model).contains("Regenerated 3 image(s)"),
                "Expected the regenerated count in the confirmation, got: " + messages(model));
    }

    @Test
    void aFailedRenditionRegenerationIsReported() throws Exception {
        doThrow(new WebloggerException("disk full"))
                .when(weblogger.getMediaFileManager()).regenerateRenditions(any());

        String view = controller.regenerateRenditions(request, model);

        assertEquals(".Maintenance", view);
        assertTrue(errors(model).contains("maintenance.message.renditionsRegenerated.failure"),
                "Expected a rendition-regeneration failure, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed regeneration must not also report success");
    }
}
