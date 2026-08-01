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
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileImageDimController}, which backs the "choose the
 * displayed image size" overlay when inserting a media file into an entry.
 *
 * <p>The bean it hands to the view has to be populated from the real media
 * file (width/height/thumbnail URL), or the dimension picker would offer
 * meaningless numbers. A lookup failure must not take down the overlay.
 */
class MediaFileImageDimControllerTest extends EditorControllerTestSupport {

    private MediaFileImageDimController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new MediaFileImageDimController());
        model = newModel();
    }

    @Test
    void theBeanIsPopulatedFromTheLookedUpMediaFile() throws Exception {
        MediaFileDirectory directory = new MediaFileDirectory();
        directory.setId("dir-1");
        directory.setName("default");
        directory.setWeblog(weblog);

        MediaFile file = new MediaFile();
        file.setId("file-1");
        file.setName("photo.jpg");
        file.setDirectory(directory);
        file.setWidth(800);
        file.setHeight(600);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(file);

        String view = controller.execute(request, model, "file-1");

        assertEquals(".MediaFileImageDimension", view);
        MediaFileBean bean = (MediaFileBean) model.getAttribute("bean");
        assertEquals("photo.jpg", bean.getName());
        assertEquals(800, bean.getWidth());
        assertEquals(600, bean.getHeight());
    }

    @Test
    void aFailedLookupLeavesTheModelWithoutABeanRatherThanBlowingUp() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("missing"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, "missing");

        assertEquals(".MediaFileImageDimension", view);
        assertNull(model.getAttribute("bean"),
                "A failed lookup must not leave a half-populated bean in the model");
    }
}
