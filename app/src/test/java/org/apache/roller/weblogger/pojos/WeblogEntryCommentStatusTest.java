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
package org.apache.roller.weblogger.pojos;

import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the derived flags on {@link WeblogEntryComment}.
 *
 * <p>{@code getPending} and {@code getApproved} are views onto a single
 * {@code status} field, and themes and the moderation UI branch on them
 * directly. If both can be true at once -- or if the wrong one is true -- a
 * comment the owner rejected gets rendered on the public blog.
 */
class WeblogEntryCommentStatusTest {

    private static WeblogEntryComment comment(ApprovalStatus status) {
        WeblogEntryComment comment = new WeblogEntryComment();
        comment.setStatus(status);
        return comment;
    }

    @Test
    void exactlyOneDerivedFlagIsTrueForEachStatus() {
        WeblogEntryComment approved = comment(ApprovalStatus.APPROVED);
        assertTrue(approved.getApproved(), "APPROVED must read as approved");
        assertFalse(approved.getPending(), "An approved comment is not awaiting moderation");

        WeblogEntryComment pending = comment(ApprovalStatus.PENDING);
        assertTrue(pending.getPending());
        assertFalse(pending.getApproved(),
                "A comment still in the moderation queue must not read as approved");

        WeblogEntryComment rejected = comment(ApprovalStatus.DISAPPROVED);
        assertFalse(rejected.getApproved(),
                "A comment the owner rejected must not read as approved -- getApproved() "
                        + "is what the theme checks before rendering a comment publicly");
        assertFalse(rejected.getPending(),
                "Rejected is a decision, not a pending one");
    }

    /**
     * There is no SPAM status to fall back to. Marking a comment as spam
     * deletes it, so the only states a stored comment can be in are these
     * three -- a fourth would be a status the moderation screens do not
     * render and the public query does not know how to treat.
     */
    @Test
    void theOnlyStatusesAreApprovedDisapprovedAndPending() {
        assertEquals(3, ApprovalStatus.values().length,
                "Adding a status means teaching every comment query and both moderation "
                        + "screens what to do with it: " + java.util.Arrays.toString(
                                ApprovalStatus.values()));
    }

    @Test
    void commentsDefaultToPlainTextSoTheirContentGetsEscaped() {
        // The wrapper escapes the body only when the content type says text/plain.
        // Defaulting to anything else would hand raw HTML from an anonymous
        // commenter straight into the page.
        assertEquals("text/plain", new WeblogEntryComment().getContentType(),
                "A comment with no explicit content type must be treated as plain text, "
                        + "which is what makes WeblogEntryCommentWrapper escape its body");
    }

    @Test
    void thePermalinkTimestampIsTheEpochMillisOfThePostTime() {
        // Comment permalinks are "<entry permalink>#comment-<timestamp>", so the
        // value has to be stable and derived from the stored post time.
        WeblogEntryComment comment = comment(ApprovalStatus.APPROVED);
        Timestamp posted = Timestamp.valueOf("2024-03-09 15:30:00");
        comment.setPostTime(posted);

        assertEquals(Long.toString(posted.getTime()), comment.getTimestamp());
    }

    @Test
    void anUnpostedCommentHasNoPermalinkTimestamp() {
        WeblogEntryComment comment = comment(ApprovalStatus.APPROVED);
        comment.setPostTime(null);

        assertNull(comment.getTimestamp(),
                "A comment with no post time has no permalink anchor; returning '0' or "
                        + "the current time would make every such comment collide");
    }
}
