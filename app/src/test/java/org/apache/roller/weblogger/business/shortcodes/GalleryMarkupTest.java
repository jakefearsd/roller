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
package org.apache.roller.weblogger.business.shortcodes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aspect-ratio class the gallery grid packs rows with.
 *
 * <p>The ratio cannot ride as an inline {@code --ar} custom property: the
 * sanitizer's CSS schema rejects custom properties outright, so the value is
 * quantized into an {@code ar-NNN} class and turned back into {@code --ar} by
 * rules in the theme macro. That split is only safe while the Java constants
 * and the generated stylesheet agree, which is what the last test here checks.
 */
class GalleryMarkupTest {

    @Test
    void ratiosQuantizeToTheNearestStep() {
        assertEquals("ar-135", GalleryMarkup.aspectRatioClass(1.3405),
                "1.3405 rounds to 1.35, not down to 1.30");
        assertEquals("ar-150", GalleryMarkup.aspectRatioClass(1.5));
        assertEquals("ar-100", GalleryMarkup.aspectRatioClass(1.0), "a square");
        assertEquals("ar-65", GalleryMarkup.aspectRatioClass(0.6666), "3:2 portrait");
    }

    @Test
    void extremeRatiosClampRatherThanFallBack() {
        // A panorama clamped to the widest rule still packs roughly right;
        // falling through to the 1.5 default would be wrong the other way.
        assertEquals("ar-260", GalleryMarkup.aspectRatioClass(6.0), "a panorama");
        assertEquals("ar-40", GalleryMarkup.aspectRatioClass(0.2), "a very tall portrait");
    }

    @Test
    void theStylesheetHasARuleForEveryClassTheEmitterCanProduce() throws IOException {
        // The coupling that would otherwise rot silently: a figure carrying a
        // class the stylesheet has no rule for falls back to --ar: 1.5 and
        // packs at the wrong width, with nothing failing anywhere.
        Path macro = Paths.get("src/main/webapp/WEB-INF/velocity/weblog.vm");
        assertTrue(Files.exists(macro), "expected the macro library at " + macro.toAbsolutePath());
        String css = Files.readString(macro, StandardCharsets.UTF_8);

        for (int h = GalleryMarkup.MIN_AR_CLASS; h <= GalleryMarkup.MAX_AR_CLASS;
                h += GalleryMarkup.AR_CLASS_STEP) {
            String rule = ".jgrid figure.ar-" + h + "{";
            assertTrue(css.contains(rule),
                    "no stylesheet rule for " + rule + " -- regenerate the ar-NNN table in "
                            + "#showGalleryGridStyles from GalleryMarkup's constants");
        }
    }
}
