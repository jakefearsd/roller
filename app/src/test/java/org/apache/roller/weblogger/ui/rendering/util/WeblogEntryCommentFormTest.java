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

import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WeblogEntryCommentForm}, the object a weblog template
 * reads when re-rendering the comment box after a failed or previewed post.
 *
 * <p>Every getter on this class escapes on the way out. That is the last line
 * of defence for the round-trip case: a comment that was rejected still has its
 * text put straight back into the form, so unescaped markup here is reflected
 * XSS on the permalink page.
 */
class WeblogEntryCommentFormTest {

    private static final String SCRIPT = "<script>alert('xss')</script>";

    @Test
    void freshFormIsEmptyAndNotInAnErrorOrPreviewState() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();

        assertEquals("", form.getName(), "A fresh form must render empty inputs, not \"null\"");
        assertEquals("", form.getEmail());
        assertEquals("", form.getUrl());
        assertEquals("", form.getContent());
        assertFalse(form.isNotify());
        assertFalse(form.isError());
        assertFalse(form.isPreview());
        assertNull(form.getMessage());
    }

    @Test
    void everyRoundTrippedFieldIsHtmlEscapedOnTheWayOut() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();
        form.setName(SCRIPT);
        form.setEmail(SCRIPT);
        form.setUrl(SCRIPT);
        form.setContent(SCRIPT);

        assertEquals("&lt;script&gt;alert('xss')&lt;/script&gt;", form.getName(),
                "The author name is echoed into the form and must be escaped");
        assertEquals("&lt;script&gt;alert('xss')&lt;/script&gt;", form.getEmail(),
                "The email is echoed into the form and must be escaped");
        assertEquals("&lt;script&gt;alert('xss')&lt;/script&gt;", form.getUrl(),
                "The url is echoed into the form and must be escaped");
        assertEquals("&lt;script&gt;alert('xss')&lt;/script&gt;", form.getContent(),
                "The comment body is echoed into the textarea and must be escaped");
    }

    @Test
    void escapingDoesNotMangleOrdinaryText() {
        // Guards the guard: escaping that also rewrote plain text would be
        // visible to every commenter whose post was rejected once.
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();
        form.setName("Ada Lovelace");
        form.setContent("Great post, thanks!");

        assertEquals("Ada Lovelace", form.getName());
        assertEquals("Great post, thanks!", form.getContent());
    }

    @Test
    void setDataCopiesEveryFieldOffTheComment() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();

        form.setData(comment());

        assertEquals("Ada", form.getName());
        assertEquals("ada@example.com", form.getEmail());
        assertEquals("http://example.com", form.getUrl());
        assertEquals("hello", form.getContent());
        assertTrue(form.isNotify(),
                "The notify choice must survive the round trip or the commenter "
                        + "silently loses their subscription when a post is retried");
    }

    @Test
    void previewPopulatesTheFormAndFlipsThePreviewFlag() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();

        form.setPreview(comment());

        assertTrue(form.isPreview(), "A previewed comment must report itself as a preview");
        assertNotNull(form.getPreviewComment(),
                "The template renders the previewed comment through this wrapper");
        assertEquals("Ada", form.getName(),
                "Previewing must also refill the form so the commenter can keep editing");
    }

    @Test
    void withoutAPreviewTheWrappedCommentIsNullRatherThanAnEmptyWrapper() {
        // The template checks isPreview() first, but a null-safe wrapper here
        // means a template that forgets to check gets nothing rather than an
        // exception mid-render.
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();

        assertNull(form.getPreviewComment());
    }

    @Test
    void recordingAnErrorSetsBothTheFlagAndTheMessage() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();

        form.setError("comments.disabled");

        assertTrue(form.isError(),
                "Templates branch on isError(); a message with the flag unset is invisible");
        assertEquals("comments.disabled", form.getMessage());
    }

    @Test
    void errorFlagCanBeClearedIndependentlyOfTheMessage() {
        WeblogEntryCommentForm form = new WeblogEntryCommentForm();
        form.setError("comments.disabled");

        form.setError(false);

        assertFalse(form.isError());
        assertEquals("comments.disabled", form.getMessage(),
                "Clearing the flag leaves the message; the two setters are independent");
    }

    private static WeblogEntryComment comment() {
        WeblogEntryComment comment = new WeblogEntryComment();
        comment.setName("Ada");
        comment.setEmail("ada@example.com");
        comment.setUrl("http://example.com");
        comment.setContent("hello");
        comment.setNotify(Boolean.TRUE);
        return comment;
    }
}
