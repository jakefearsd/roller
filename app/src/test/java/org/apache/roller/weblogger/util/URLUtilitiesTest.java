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

package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the URL helpers behind every link the URL strategy builds.
 *
 * <p>These decide what a permalink, a tag page and a feed URL look like, so
 * they are effectively a compatibility contract: change the encoding and
 * previously published links stop resolving.
 */
public class URLUtilitiesTest {

    @Test
    public void buildsAQueryStringWithTheFirstParameterAfterAQuestionMark() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cat", "news");
        params.put("page", "2");
        assertEquals("?cat=news&page=2", URLUtilities.getQueryString(params));
    }

    @Test
    public void aSingleParameterStillGetsTheQuestionMark() {
        assertEquals("?cat=news", URLUtilities.getQueryString(Map.of("cat", "news")));
    }

    @Test
    public void anEmptyParameterMapProducesNoQueryStringAtAll() {
        // Appending a bare "?" to a permalink would create a second URL for
        // the same page, which search engines treat as duplicate content.
        assertEquals("", URLUtilities.getQueryString(Map.of()));
    }

    @Test
    public void aNullParameterMapGivesNullSoCallersCanSkipTheSuffix() {
        assertNull(URLUtilities.getQueryString(null));
    }

    @Test
    public void encodeUsesFormEncodingIncludingPlusForSpace() {
        // URLEncoder is form encoding, not path encoding: a space becomes '+'
        // rather than %20. Everything that consumes these URLs decodes them
        // the same way, so the pair is self-consistent -- but it is worth
        // pinning, because switching to %20 would silently change every tag
        // link Roller has ever published.
        assertEquals("a+b", URLUtilities.encode("a b"));
        assertEquals("a%26b", URLUtilities.encode("a&b"));
        assertEquals("a%2Fb", URLUtilities.encode("a/b"));
    }

    @Test
    public void encodeAndDecodeRoundTripNonAsciiText() {
        // Tags and category names are user text in any script; the UTF-8
        // round trip is what keeps a Cyrillic tag page reachable.
        String original = "café 中文 & more";
        assertEquals(original, URLUtilities.decode(URLUtilities.encode(original)));
    }

    @Test
    public void encodePathLeavesTheSlashesIntact() {
        // Encoding the separators would turn a folder path into one long
        // filename and break media file URLs.
        assertEquals("/a+b/c%26d", URLUtilities.encodePath("/a b/c&d"));
        assertEquals("plain", URLUtilities.encodePath("plain"));
        assertEquals("", URLUtilities.encodePath(""));
    }

    @Test
    public void encodePathKeepsTrailingAndDoubledSeparators() {
        assertEquals("a/", URLUtilities.encodePath("a/"));
        assertEquals("a//b", URLUtilities.encodePath("a//b"));
    }

    @Test
    public void encodedTagsAreJoinedWithPlusSigns() {
        // The tag URL syntax is /tags/foo+bar, so the join character is part
        // of the route and not just cosmetic.
        assertEquals("java+roller", URLUtilities.getEncodedTagsString(List.of("java", "roller")));
        assertEquals("java", URLUtilities.getEncodedTagsString(List.of("java")));
    }

    @Test
    public void tagsAreIndividuallyEncodedBeforeBeingJoined() {
        assertEquals("a+b+c%26d", URLUtilities.getEncodedTagsString(List.of("a b", "c&d")));
    }

    @Test
    public void noTagsMeansAnEmptyStringRatherThanNull() {
        // The result is concatenated straight into a URL.
        assertEquals("", URLUtilities.getEncodedTagsString(null));
        assertEquals("", URLUtilities.getEncodedTagsString(List.of()));
    }
}
