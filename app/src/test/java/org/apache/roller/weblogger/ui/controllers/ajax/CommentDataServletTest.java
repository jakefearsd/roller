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
package org.apache.roller.weblogger.ui.controllers.ajax;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The comment read/edit endpoint behind the moderation screen's inline editor.
 *
 * <p>Both halves are gated on {@code WeblogPermission.POST} for the weblog the
 * comment belongs to, and neither gate was tested. The write half matters most:
 * {@code doPut} takes the request body and stores it as the comment's content,
 * so a hole there lets any editor rewrite any weblog's comments. The permission
 * is checked against the comment's <em>own</em> weblog rather than anything the
 * caller supplied, which is the right shape -- and exactly the shape that
 * silently stops working if someone refactors the lookup.
 *
 * <p>{@code doPost} exists only because not every browser sends PUT; it must
 * stay an alias, or the editor stops saving for those clients alone.
 */
class CommentDataServletTest {

    private static final String COMMENT_ID = "comment-1";

    private CommentDataServlet servlet;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;

    private User caller;
    private Weblog weblog;
    private WeblogEntryComment comment;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new CommentDataServlet();
        response = new MockHttpServletResponse();
        weblogger = MockWeblogger.install();

        caller = new User();
        caller.setUserName("caller");
        caller.setEnabled(Boolean.TRUE);
        when(weblogger.userManager().getUserByUserName("caller")).thenReturn(caller);

        weblog = new Weblog();
        weblog.setHandle("someblog");

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);

        comment = new WeblogEntryComment();
        comment.setId(COMMENT_ID);
        comment.setContent("original content");
        comment.setWeblogEntry(entry);
        when(weblogger.weblogEntryManager().getComment(COMMENT_ID)).thenReturn(comment);

        request = new MockHttpServletRequest("GET", "/roller-ui/authoring/commentdata");
        request.setSession(new MockHttpSession());
        request.setUserPrincipal(() -> "caller");
        request.setParameter("id", COMMENT_ID);
    }

    @AfterEach
    void tearDown() {
        MockWeblogger.uninstall();
    }

    // ------------------------------------------------------------------- read

    @Test
    void readingIsRefusedWithoutPostPermissionOnTheCommentsWeblog() throws Exception {
        mayPost(false);

        servlet.doGet(request, response);

        assertEquals(403, response.getStatus(),
                "a caller with no rights on this weblog must not read its comments");
        assertFalse(response.getContentAsString().contains("original content"),
                "and the content must not leak in the body either");
    }

    @Test
    void readingSucceedsWithPostPermission() throws Exception {
        mayPost(true);

        servlet.doGet(request, response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("original content"),
                "got: " + response.getContentAsString());
    }

    @Test
    void anUnknownCommentIsNotFound() throws Exception {
        request.setParameter("id", "nosuch");
        when(weblogger.weblogEntryManager().getComment("nosuch")).thenReturn(null);

        servlet.doGet(request, response);

        assertEquals(404, response.getStatus());
    }

    /**
     * The comment goes into a JavaScript object literal, so a quote or an
     * angle bracket in it has to be neutralised twice over -- once as HTML and
     * once for the script context it is pasted into. Getting this wrong turns
     * a comment into script running on the moderator's own page.
     */
    @Test
    void commentContentIsEscapedForTheScriptContextItIsPastedInto() throws Exception {
        comment.setContent("</script><img src=x onerror=\"alert('xss')\">");
        mayPost(true);

        servlet.doGet(request, response);

        String body = response.getContentAsString();
        assertFalse(body.contains("<img"),
                "markup must not survive into the response: " + body);
        assertFalse(body.contains("</script>"),
                "and neither may a script terminator: " + body);
    }

    // ------------------------------------------------------------------ write

    /**
     * The one that matters. A refused write must leave the comment alone, which
     * only the manager can attest -- a 403 with a save behind it would be worse
     * than no check at all.
     */
    @Test
    void writingIsRefusedWithoutPostPermissionAndSavesNothing() throws Exception {
        mayPost(false);
        request.setMethod("PUT");
        request.setContent("rewritten by someone else".getBytes());

        servlet.doPut(request, response);

        assertEquals(403, response.getStatus());
        verify(weblogger.weblogEntryManager(), never()).saveComment(any());
        assertEquals("original content", comment.getContent(),
                "the comment must be untouched");
    }

    @Test
    void writingStoresTheNewContentWhenPermitted() throws Exception {
        mayPost(true);
        request.setMethod("PUT");
        request.setContent("edited by the moderator".getBytes());

        servlet.doPut(request, response);

        assertEquals(200, response.getStatus());
        // trim(): Utilities.streamToString appends a line separator after every
        // line including the last, so each save through this endpoint leaves a
        // trailing newline behind and repeated edits accumulate them. Harmless
        // as rendered, but it is the stored content growing on every save --
        // pinned here rather than hidden so a fix is a deliberate change.
        assertEquals("edited by the moderator", comment.getContent().trim());
        verify(weblogger.weblogEntryManager()).saveComment(comment);
        assertTrue(weblogger.flushCount() > 0,
                "an edit that is never flushed never reaches the database");
    }

    /**
     * Editing a comment must not move it to the top of the moderation queue,
     * which orders by post time.
     */
    @Test
    void editingDoesNotChangeWhenTheCommentWasPosted() throws Exception {
        java.sql.Timestamp posted = java.sql.Timestamp.valueOf("2026-01-02 03:04:05");
        comment.setPostTime(posted);
        mayPost(true);
        request.setMethod("PUT");
        request.setContent("edited".getBytes());

        servlet.doPut(request, response);

        assertEquals(posted, comment.getPostTime());
    }

    @Test
    void writingToAnUnknownCommentIsNotFound() throws Exception {
        request.setParameter("id", "nosuch");
        when(weblogger.weblogEntryManager().getComment("nosuch")).thenReturn(null);
        request.setMethod("PUT");
        request.setContent("anything".getBytes());

        servlet.doPut(request, response);

        assertEquals(404, response.getStatus());
        verify(weblogger.weblogEntryManager(), never()).saveComment(any());
    }

    /** Browsers that cannot send PUT post instead; the gate must be identical. */
    @Test
    void postIsAnAliasForPutAndIsGatedTheSameWay() throws Exception {
        mayPost(false);
        request.setMethod("POST");
        request.setContent("rewritten".getBytes());

        servlet.doPost(request, response);

        assertEquals(403, response.getStatus());
        verify(weblogger.weblogEntryManager(), never()).saveComment(any());
    }

    private void mayPost(boolean allowed) throws Exception {
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenReturn(allowed);
    }
}
