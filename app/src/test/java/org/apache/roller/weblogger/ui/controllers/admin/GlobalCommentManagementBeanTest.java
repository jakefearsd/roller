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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.Calendar;

import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link GlobalCommentManagementBean}, which turns what the admin
 * typed into the query the comment search actually runs.
 *
 * <p>Every branch here decides which comments an admin sees -- and, on the bulk
 * delete path, which comments get destroyed -- so a filter that quietly widens
 * to "everything" matters.
 */
class GlobalCommentManagementBeanTest {

    @Test
    void theDefaultFilterMatchesCommentsOfEveryStatus() {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();

        assertEquals("ALL", bean.getApprovedString());
        assertNull(bean.getStatus(), "a null status is what the query layer reads as 'no status filter'");
        assertEquals(0, bean.getPage());
    }

    @Test
    void eachFilterChoiceMapsToItsApprovalStatus() {
        assertEquals(ApprovalStatus.APPROVED, statusFor("ONLY_APPROVED"));
        assertEquals(ApprovalStatus.DISAPPROVED, statusFor("ONLY_DISAPPROVED"));
        assertEquals(ApprovalStatus.PENDING, statusFor("ONLY_PENDING"));
    }

    @Test
    void theRetiredSpamFilterFallsBackToShowingEverything() {
        // ONLY_SPAM was an option until spam became a deletion; a stale
        // bookmark carrying it must widen to everything, not throw.
        assertNull(statusFor("ONLY_SPAM"));
    }

    @Test
    void anUnrecognisedFilterChoiceFallsBackToShowingEverything() {
        // Rather than showing nothing, which would look like "no comments here".
        assertNull(statusFor("ONLY_TUESDAYS"));
    }

    @Test
    void thePendingAliasIsTheSameFieldAsTheApprovedFilter() {
        // The JSP binds to pendingString; the query reads approvedString.
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setPendingString("ONLY_PENDING");

        assertEquals("ONLY_PENDING", bean.getApprovedString());
        assertEquals("ONLY_PENDING", bean.getPendingString());
        assertEquals(ApprovalStatus.PENDING, bean.getStatus());
    }

    @Test
    void aStartDateIsReadAsTheBeginningOfThatDay() {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setStartDateString("03/14/24");

        Calendar cal = Calendar.getInstance();
        cal.setTime(bean.getStartDate());
        assertEquals(2024, cal.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH));
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    void anEndDateStretchesToTheLastMillisecondOfThatDay() {
        // Otherwise a search "through 14 March" would drop everything posted
        // on the 14th after midnight.
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setEndDateString("03/14/24");

        Calendar cal = Calendar.getInstance();
        cal.setTime(bean.getEndDate());
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(23, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(59, cal.get(Calendar.MINUTE));
        assertEquals(59, cal.get(Calendar.SECOND));
    }

    @Test
    void noDatesTypedMeansNoDateBounds() {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();

        assertNull(bean.getStartDate());
        assertNull(bean.getEndDate());
    }

    @Test
    void gibberishInADateFieldIsIgnoredRatherThanThrown() {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setStartDateString("not a date");
        bean.setEndDateString("also not a date");

        assertNull(bean.getStartDate());
        assertNull(bean.getEndDate());
    }

    @Test
    void theCheckboxArraysCannotBeChangedFromOutsideTheBean() {
        // They drive deletion, so a caller holding a live reference to the
        // internal array could change what gets deleted after validation.
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        String[] deletes = {"c1"};
        bean.setDeleteComments(deletes);

        deletes[0] = "c2";
        bean.getDeleteComments()[0] = "c3";

        assertArrayEquals(new String[]{"c1"}, bean.getDeleteComments());
    }

    private static ApprovalStatus statusFor(String approvedString) {
        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        bean.setApprovedString(approvedString);
        return bean.getStatus();
    }

}
