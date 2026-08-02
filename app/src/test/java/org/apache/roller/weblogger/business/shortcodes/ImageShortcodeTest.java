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

import java.util.Map;
import java.util.function.Supplier;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code [image]} shortcode: srcset built from the rendition
 * ladder, honest fallbacks for pre-ladder uploads, and null (leave the
 * shortcode as written) whenever the media file cannot serve an image.
 */
class ImageShortcodeTest {

    private static final String URL = "http://example.com/roller/blog/mediaresource/mf-1";

    private final ImageShortcode shortcode = new ImageShortcode();
    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;
    private MediaFileManager mediaFileManager;
    private MediaFile photo;

    @BeforeEach
    void setUp() throws Exception {
        weblog = new Weblog();
        weblog.setHandle("blog");
        entry = new WeblogEntry();
        entry.setWebsite(weblog);

        photo = new MediaFile();
        photo.setId("mf-1");
        photo.setWeblog(weblog);
        photo.setName("hawk.jpg");
        photo.setContentType("image/jpeg");
        photo.setWidth(1200);
        photo.setHeight(800);

        mediaFileManager = mock(MediaFileManager.class);
        when(mediaFileManager.getMediaFile("mf-1")).thenReturn(photo);

        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getMediaFileURL(weblog, "mf-1", true)).thenReturn(URL);

        weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
    }

    private <T> T withWeblogger(Supplier<T> body) {
        try (MockedStatic<WebloggerFactory> factory = mockStatic(WebloggerFactory.class)) {
            factory.when(WebloggerFactory::getWeblogger).thenReturn(weblogger);
            return body.get();
        }
    }

    private String render(Map<String, String> attributes, String body) {
        return withWeblogger(() -> shortcode.render(attributes, body, entry));
    }

    // -------------------------------------------------------------- happy path

    @Test
    void emitsAResponsiveFigurePictureBlock() {
        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.startsWith("<figure class=\"shortcode-image\">"), html);
        assertTrue(html.contains("<picture>"), html);
        assertTrue(html.contains("<source type=\"image/webp\" srcset=\""), html);
        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertTrue(html.contains(" loading=\"lazy\""), html);
        assertTrue(html.endsWith("</figure>"), html);
    }

    @Test
    void srcsetClimbsTheLadderOnlyBelowTheOriginalWidth() {
        // 1200w original: 480 and 960 rungs exist, 1600/2400 were never
        // generated (RenditionSupport never upscales) so they must not be
        // offered; the original itself is the largest candidate.
        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains(URL + "?w=480 480w"), html);
        assertTrue(html.contains(URL + "?w=960 960w"), html);
        assertFalse(html.contains("?w=1600"), html);
        assertFalse(html.contains("?w=2400"), html);
        assertTrue(html.contains(URL + " 1200w"), html);
    }

    @Test
    void emitsIntrinsicDimensionsToPreventLayoutShift() {
        String html = render(Map.of("id", "mf-1"), null);
        assertTrue(html.contains(" width=\"1200\" height=\"800\""), html);
    }

    @Test
    void aFormatOutsideTheLadderGetsAPlainImg() {
        // The ladder only covers jpeg/png. For a gif every ?w= URL silently
        // serves the full-size original, so a srcset -- and especially a
        // <source type="image/webp"> -- would lie to the browser about what
        // those URLs return.
        photo.setContentType("image/gif");

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertFalse(html.contains("srcset"), html);
        assertFalse(html.contains("image/webp"), html);
        assertTrue(html.contains(" width=\"1200\" height=\"800\""),
                "known dimensions still prevent layout shift:\n" + html);
    }

    @Test
    void aPreLadderUploadWithoutWidthMetadataGetsAPlainImg() {
        photo.setWidth(0);
        photo.setHeight(0);

        String html = render(Map.of("id", "mf-1"), null);

        assertTrue(html.contains("<img src=\"" + URL + "\""), html);
        assertFalse(html.contains("srcset"),
                "without width metadata we cannot know which rungs exist; a "
                        + "plain src always renders:\n" + html);
        assertFalse(html.contains("width=\""), html);
    }

    // ------------------------------------------------------- captions and alt

    @Test
    void theCaptionAttributeBecomesAnEscapedFigcaption() {
        String html = render(Map.of("id", "mf-1", "caption", "A \"red\" hawk"), null);
        assertTrue(html.contains("<figcaption>A &quot;red&quot; hawk</figcaption>"), html);
    }

    @Test
    void aBodyBecomesTheFigcaptionVerbatim() {
        // Body text is entry HTML like any other; the downstream sanitizer
        // decides what markup survives, exactly as it does for the rest of
        // the entry.
        String html = render(Map.of("id", "mf-1"), "A <em>proud</em> hawk");
        assertTrue(html.contains("<figcaption>A <em>proud</em> hawk</figcaption>"), html);
    }

    @Test
    void withoutACaptionThereIsNoFigcaption() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("figcaption"));
    }

    @Test
    void altComesFromTheAttributeAndFallsBackToTheFileName() {
        assertTrue(render(Map.of("id", "mf-1", "alt", "Hawk in flight"), null)
                .contains(" alt=\"Hawk in flight\""));
        assertTrue(render(Map.of("id", "mf-1"), null).contains(" alt=\"hawk.jpg\""));
    }

    // -------------------------------------------------------------- blurhash

    @Test
    void blurhashAppearsOnlyWhenTheMediaFileHasOne() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("data-blurhash"),
                "files uploaded before the rendition pipeline have no blurhash; "
                        + "the attribute must be omitted, not emitted empty");

        photo.setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
        assertTrue(render(Map.of("id", "mf-1"), null)
                .contains(" data-blurhash=\"LEHV6nWB2yk8pyo0adR*.7kCMdnj\""));
    }

    @Test
    void theBlurhashAverageColorBecomesAJsFreeBackgroundPlaceholder() {
        assertFalse(render(Map.of("id", "mf-1"), null).contains("background-color"),
                "no blurhash, no placeholder color");

        photo.setBlurhash("LEHV6nWB2yk8pyo0adR*.7kCMdnj");
        String html = render(Map.of("id", "mf-1"), null);
        // DC chars "HV6n" of the fixture hash decode to this average color.
        assertTrue(html.contains(" style=\"background-color:#979695\""),
                "the average color must back the reserved image box while it loads:\n" + html);
    }

    // ------------------------------------------------------- refusal to render

    @Test
    void withoutAnIdTheShortcodeIsLeftAsWritten() {
        assertNull(render(Map.of(), null));
        assertNull(render(Map.of("id", "  "), null));
    }

    @Test
    void anUnknownMediaFileIdIsLeftAsWritten() throws Exception {
        when(mediaFileManager.getMediaFile("nope")).thenReturn(null);
        assertNull(render(Map.of("id", "nope"), null));
    }

    @Test
    void aNonImageMediaFileIsLeftAsWritten() {
        photo.setContentType("application/pdf");
        assertNull(render(Map.of("id", "mf-1"), null));
    }

    @Test
    void aFailingMediaLookupIsLeftAsWrittenRatherThanBreakingTheRender() throws Exception {
        when(mediaFileManager.getMediaFile("mf-1"))
                .thenThrow(new WebloggerException("db down"));
        assertNull(render(Map.of("id", "mf-1"), null));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void aCaptionContainingBracketsSurvivesTheParser() {
        // Review Important #2 repro: brackets inside a quoted caption are an
        // ordinary thing to type and must neither truncate the caption nor
        // leak raw shortcode syntax around the figure.
        String rendered = withWeblogger(() -> ShortcodeExpander.defaultExpander()
                .expand(entry, "[image id=mf-1 caption=\"Paris [2023]\"] after"));

        assertTrue(rendered.contains("<figcaption>Paris [2023]</figcaption>"), rendered);
        assertTrue(rendered.endsWith(" after"), rendered);
        assertFalse(rendered.contains("caption="), rendered);
        assertFalse(rendered.contains("[image"), rendered);
    }

    @Test
    void theDefaultExpanderShipsWithTheImageShortcodeRegistered() {
        String rendered = withWeblogger(() -> ShortcodeExpander.defaultExpander()
                .expand(entry, "look [image id=mf-1 caption=\"A hawk\"] here"));

        assertTrue(rendered.contains("<figure class=\"shortcode-image\">"), rendered);
        assertTrue(rendered.contains("srcset"), rendered);
        assertTrue(rendered.contains("<figcaption>A hawk</figcaption>"), rendered);
        assertFalse(rendered.contains("[image"), rendered);
        assertEquals(0, rendered.indexOf("look "), rendered);
    }
}
