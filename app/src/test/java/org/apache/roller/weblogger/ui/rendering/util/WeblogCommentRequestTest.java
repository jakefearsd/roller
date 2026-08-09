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

package org.apache.roller.weblogger.ui.rendering.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WeblogCommentRequest}.
 *
 * <p>This is the one parser that handles hostile input by default: the comment
 * form is open to anyone. The path must name exactly one entry, and the
 * author-supplied fields go through HTML stripping before anything else in the
 * application sees them.
 */
class WeblogCommentRequestTest {

    private static final String COMMENT_SERVLET = "/roller-ui/rendering/comment";

    private static WeblogCommentRequest parse(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogCommentRequest(MockRequest.with(COMMENT_SERVLET, pathInfo, params));
    }

    @Test
    void requestAimedAtAnotherServletIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogCommentRequest(
                        MockRequest.with("/roller-ui/rendering/page", "/myblog/entry/hello")),
                "A page URL must not parse as a comment post");
    }

    @Test
    void requestWithNoServletPathIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogCommentRequest(MockRequest.with(null, "/myblog/entry/hello")),
                "A null servlet path cannot match the comment servlet");
    }

    @Test
    void entryPathIdentifiesTheEntryBeingCommentedOn() throws Exception {
        WeblogCommentRequest request = parse("/myblog/entry/hello-world");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("hello-world", request.getWeblogAnchor());
    }

    @Test
    void anchorIsPercentDecoded() throws Exception {
        WeblogCommentRequest request = parse("/myblog/entry/c%2B%2B-tips");

        assertEquals("c++-tips", request.getWeblogAnchor(),
                "The anchor must decode the same way it does on the permalink page, "
                        + "or the comment attaches to no entry");
    }

    @Test
    void malformedPercentEscapeInTheAnchorIsRejectedAsAnInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entry/50%"),
                "A bare '%' must produce InvalidRequestException rather than an "
                        + "unchecked IllegalArgumentException");
    }

    @Test
    void missingPathIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog"),
                "A comment must name the entry it belongs to");
    }

    @Test
    void wrongNumberOfPathSegmentsIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entry"),
                "\"entry\" with no anchor names no entry");
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entry/a/b"),
                "Three segments is not a comment path");
    }

    @Test
    void contextOtherThanEntryIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/category/Java"),
                "Comments attach to entries only, never to a category listing");
    }

    // ------------------------------------------------------------ form fields

    @Test
    void authorSuppliedFieldsHaveHtmlStripped() throws Exception {
        // These two land in the comment record and are rendered back on the
        // permalink page. Stripping here is the first of the defences against
        // stored XSS.
        WeblogCommentRequest request = parse("/myblog/entry/hello",
                "name", "<script>alert(1)</script>Bob",
                "email", "<b>bob</b>@example.com");

        assertFalse(request.getName().contains("<"),
                "Markup must not survive in the comment author name; got: " + request.getName());
        assertTrue(request.getName().contains("Bob"),
                "Stripping must keep the actual text");
        assertFalse(request.getEmail().contains("<"),
                "Markup must not survive in the comment email; got: " + request.getEmail());
    }

    @Test
    void commentBodyIsKeptVerbatimForLaterPluginProcessing() throws Exception {
        // Unlike the header fields the body is deliberately untouched here --
        // the comment plugins and the escaping done at render time own it. If
        // this ever starts stripping, comments containing code samples silently
        // lose their content.
        String body = "Try <b>this</b> instead";
        WeblogCommentRequest request = parse("/myblog/entry/hello", "content", body);

        assertEquals(body, request.getContent(),
                "The comment body must reach the plugins exactly as submitted");
    }

    @Test
    void notifyFlagIsSetByThePresenceOfTheParameterRatherThanItsValue() throws Exception {
        // This is how an unchecked HTML checkbox behaves: it is simply absent.
        // The parser therefore keys off presence, which means even
        // "notify=false" subscribes the commenter.
        assertTrue(parse("/myblog/entry/hello", "notify", "on").isNotify());
        assertTrue(parse("/myblog/entry/hello", "notify", "false").isNotify(),
                "Presence alone sets the flag; a client that sends notify=false "
                        + "still gets subscribed");
        assertFalse(parse("/myblog/entry/hello").isNotify(),
                "An absent notify parameter must leave the commenter unsubscribed");
    }

    @Test
    void absentFormFieldsStayNull() throws Exception {
        WeblogCommentRequest request = parse("/myblog/entry/hello");

        assertNull(request.getName());
        assertNull(request.getEmail());
        assertNull(request.getContent());
    }

    @Test
    void entryLookupIsSkippedWhenThereIsNoAnchor() {
        // getWeblogEntry() reaches the business tier, which is not bootstrapped
        // in a unit test. The null-anchor guard is what keeps a default-
        // constructed request from getting there.
        WeblogCommentRequest request = new WeblogCommentRequest();

        assertNull(request.getWeblogEntry(),
                "With no anchor there is nothing to look up");
    }

    @Test
    void injectedEntryIsReturnedWithoutALookup() {
        WeblogCommentRequest request = new WeblogCommentRequest();
        org.apache.roller.weblogger.pojos.WeblogEntry entry =
                new org.apache.roller.weblogger.pojos.WeblogEntry();
        request.setWeblogEntry(entry);

        assertEquals(entry, request.getWeblogEntry());
    }
}
