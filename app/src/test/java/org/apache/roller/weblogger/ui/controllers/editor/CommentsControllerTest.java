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

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.controllers.pagers.CommentsPager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CommentsController}.
 *
 * <p>The three POST handlers here are the ones worth pinning: {@code query}
 * decides whether the "select all matching" bulk-delete affordance appears at
 * all, {@code delete} is destructive across an arbitrary number of comments in
 * one call, and {@code update} folds three independent per-comment decisions
 * (delete, approve, mark spam, else disapprove) plus a reindex side effect into
 * a single loop where getting the precedence wrong is easy and silent.
 */
class CommentsControllerTest extends EditorControllerTestSupport {

    private CommentsController controller;
    private Model model;
    private String previousSearchEnabled;

    @BeforeEach
    void setUp() {
        controller = prepare(new CommentsController());
        model = newModel();
    }

    @AfterEach
    void restoreSearchEnabled() {
        if (previousSearchEnabled != null) {
            overrideConfigProperty("search.enabled", previousSearchEnabled);
            previousSearchEnabled = null;
        }
    }

    // --- getBean ---

    @Test
    void getBeanReturnsAFreshBean() {
        CommentsBean bean = controller.getBean();
        assertEquals(0, bean.getPage(), "A freshly constructed bean must start on page 0");
    }

    // --- execute ---

    @Test
    void executeLoadsThePagerAndTheBeanCheckboxes() throws Exception {
        WeblogEntryComment approved = commentNamed("c-1", ApprovalStatus.APPROVED);
        WeblogEntryComment spam = commentNamed("c-2", ApprovalStatus.SPAM);
        WeblogEntryComment pending = commentNamed("c-3", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenReturn(List.of(approved, spam, pending));

        CommentsBean bean = new CommentsBean();
        String view = controller.execute(request, model, bean);

        assertEquals(".Comments", view);
        assertEquals(3, ((CommentsPager) model.getAttribute("pager")).getItems().size(),
                "All three comments returned by the manager must reach the pager");
        assertEquals("c-1,c-2,c-3", bean.getIds(),
                "loadCheckboxes must record every comment id on the page");
        assertEquals(List.of("c-1"), List.of(bean.getApprovedComments()),
                "Only the approved comment should be pre-checked as approved");
        assertEquals(List.of("c-2"), List.of(bean.getSpamComments()),
                "Only the spam comment should be pre-checked as spam");
    }

    // --- query ---

    @Test
    void queryFlagsBulkDeleteWhenMoreThanThirtyCommentsMatch() throws Exception {
        List<WeblogEntryComment> matches = manyComments(31);
        when(weblogger.getWeblogEntryManager().getComments(any())).thenReturn(matches);

        String view = controller.query(request, model, new CommentsBean());

        assertEquals(".Comments", view);
        assertEquals(31, model.getAttribute("bulkDeleteCount"),
                "Once the match count exceeds COUNT, the bulk-delete affordance must report it");
    }

    @Test
    void queryDoesNotFlagBulkDeleteAtOrUnderTheCountThreshold() throws Exception {
        List<WeblogEntryComment> matches = manyComments(5);
        when(weblogger.getWeblogEntryManager().getComments(any())).thenReturn(matches);

        controller.query(request, model, new CommentsBean());

        assertNull(model.getAttribute("bulkDeleteCount"),
                "Five matches must not trigger the bulk-delete affordance");
    }

    @Test
    void queryReportsAnErrorInsteadOfPropagatingAManagerFailure() throws Exception {
        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenThrow(new WebloggerException("index down"));

        String view = controller.query(request, model, new CommentsBean());

        assertEquals(".Comments", view);
        assertTrue(errors(model).contains("Error looking up comments"),
                "Expected a lookup error, got: " + errors(model));
    }

    // --- delete ---

    @Test
    void deleteRemovesMatchingCommentsAndReportsTheCountThenResetsTheBean() throws Exception {
        registerMessage("commentManagement.deleteSuccess", "deleted:{0}");
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenReturn(3);

        CommentsBean bean = new CommentsBean();
        bean.setSearchString("spammy");

        String view = controller.delete(request, model, bean);

        assertEquals(".Comments", view);
        verify(weblogger.getWeblogEntryManager())
                .removeMatchingComments(weblog, null, "spammy", bean.getStartDate(),
                        bean.getEndDate(), bean.getStatus());
        assertEquals(List.of("deleted:3"), messages(model));

        CommentsBean resetBean = (CommentsBean) model.getAttribute("bean");
        assertNull(resetBean.getSearchString(),
                "The bean in the model after a delete must be a fresh one, not the posted search");
    }

    @Test
    void deleteReindexesAffectedEntriesWhenSearchIsEnabled() throws Exception {
        previousSearchEnabled = overrideConfigProperty("search.enabled", "true");

        WeblogEntry entry1 = new WeblogEntry();
        entry1.setWebsite(weblog);
        entry1.setAnchor("entry-one");
        entry1.setTitle("entry one");
        WeblogEntry entry2 = new WeblogEntry();
        entry2.setWebsite(weblog);
        entry2.setAnchor("entry-two");
        entry2.setTitle("entry two");

        WeblogEntryComment comment1 = commentNamed("c-1", ApprovalStatus.SPAM);
        comment1.setWeblogEntry(entry1);
        WeblogEntryComment comment2 = commentNamed("c-2", ApprovalStatus.SPAM);
        comment2.setWeblogEntry(entry2);

        when(weblogger.getWeblogEntryManager().getComments(any()))
                .thenReturn(List.of(comment1, comment2));
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenReturn(2);

        controller.delete(request, model, new CommentsBean());

        ArgumentCaptor<WeblogEntry> reindexed = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getIndexManager(), times(2)).addEntryReIndexOperation(reindexed.capture());
        assertTrue(reindexed.getAllValues().contains(entry1),
                "The first affected entry must be resubmitted for indexing");
        assertTrue(reindexed.getAllValues().contains(entry2),
                "The second affected entry must be resubmitted for indexing");
    }

    @Test
    void deleteReportsAnErrorInsteadOfPropagatingAManagerFailure() throws Exception {
        when(weblogger.getWeblogEntryManager()
                .removeMatchingComments(any(), any(), any(), any(), any(), any()))
                .thenThrow(new WebloggerException("boom"));

        controller.delete(request, model, new CommentsBean());

        assertTrue(errors(model).contains("Bulk delete failed due to unexpected error"),
                "Expected the bulk-delete failure message, got: " + errors(model));
        assertTrue(messages(model).isEmpty(), "A failed bulk delete must not also report success");
    }

    // --- update ---

    @Test
    void updateDeletesAListedCommentAndSkipsItFromTheApprovalLoop() throws Exception {
        WeblogEntryComment toDelete = commentNamed("c-1", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(toDelete);

        CommentsBean bean = new CommentsBean();
        bean.setDeleteComments(new String[]{"c-1"});
        // Also present in the id/approve lists; the delete must win and the
        // approval branch must never see it.
        bean.setIds("c-1");
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        verify(weblogger.getWeblogEntryManager()).removeComment(toDelete);
        verify(weblogger.getWeblogEntryManager(), never()).saveComment(any());
    }

    @Test
    void updateApprovesAPendingComment() throws Exception {
        WeblogEntryComment pending = commentNamed("c-1", ApprovalStatus.PENDING);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(pending);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");
        bean.setApprovedComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.APPROVED, pending.getStatus());
        verify(weblogger.getWeblogEntryManager()).saveComment(pending);
        assertTrue(weblogger.flushCount() > 0, "A successful update must be committed");
    }

    @Test
    void updateMarksACommentAsSpam() throws Exception {
        WeblogEntryComment approved = commentNamed("c-1", ApprovalStatus.APPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(approved);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");
        bean.setSpamComments(new String[]{"c-1"});

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.SPAM, approved.getStatus());
        verify(weblogger.getWeblogEntryManager()).saveComment(approved);
    }

    @Test
    void updateDisapprovesACommentNotInEitherList() throws Exception {
        WeblogEntryComment approved = commentNamed("c-1", ApprovalStatus.APPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(approved);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.DISAPPROVED, approved.getStatus());
        verify(weblogger.getWeblogEntryManager()).saveComment(approved);
    }

    @Test
    void updateLeavesAnAlreadyDisapprovedCommentAlone() throws Exception {
        WeblogEntryComment disapproved = commentNamed("c-1", ApprovalStatus.DISAPPROVED);
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenReturn(disapproved);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(ApprovalStatus.DISAPPROVED, disapproved.getStatus());
        verify(weblogger.getWeblogEntryManager(), never()).saveComment(disapproved);
    }

    @Test
    void updateResetsTheBeanButCarriesFiltersAndEntryIdForward() throws Exception {
        CommentsBean bean = new CommentsBean();
        bean.setIds("");
        bean.setSearchString("foo");
        bean.setStartDateString("01/01/24");
        bean.setEndDateString("02/01/24");
        bean.setApprovedString("ONLY_SPAM");
        bean.setEntryId("entry-1");

        controller.update(request, model, bean);

        CommentsBean freshBean = (CommentsBean) model.getAttribute("bean");
        assertTrue(freshBean != bean, "The bean in the model must be a new instance, not the posted one");
        assertEquals("foo", freshBean.getSearchString());
        assertEquals("01/01/24", freshBean.getStartDateString());
        assertEquals("02/01/24", freshBean.getEndDateString());
        assertEquals("ONLY_SPAM", freshBean.getApprovedString());
        assertEquals("entry-1", freshBean.getEntryId(),
                "An incoming entryId must be preserved across the reset");
    }

    @Test
    void updateLeavesEntryIdUnsetWhenNoneWasPosted() throws Exception {
        CommentsBean bean = new CommentsBean();
        bean.setIds("");

        controller.update(request, model, bean);

        CommentsBean freshBean = (CommentsBean) model.getAttribute("bean");
        assertNull(freshBean.getEntryId());
    }

    @Test
    void updateReportsTheExceptionTextInsteadOfPropagatingAFailure() throws Exception {
        registerMessage("commentManagement.updateError", "error:{0}");
        RuntimeException boom = new RuntimeException("boom");
        when(weblogger.getWeblogEntryManager().getComment("c-1")).thenThrow(boom);

        CommentsBean bean = new CommentsBean();
        bean.setIds("c-1");

        controller.update(request, model, bean);

        assertEquals(List.of("error:" + boom), errors(model));
    }

    // --- helpers ---

    private WeblogEntryComment commentNamed(String id, ApprovalStatus status) {
        WeblogEntryComment comment = new WeblogEntryComment();
        comment.setId(id);
        comment.setStatus(status);
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        comment.setWeblogEntry(entry);
        return comment;
    }

    private List<WeblogEntryComment> manyComments(int count) {
        List<WeblogEntryComment> comments = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            comments.add(commentNamed("c-" + i, ApprovalStatus.APPROVED));
        }
        return comments;
    }
}
