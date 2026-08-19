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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.pojos.MediaFileFilter.MediaFileOrder;
import org.apache.roller.weblogger.pojos.MediaFileFilter.SizeFilterType;
import org.apache.roller.weblogger.pojos.MediaFileType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the search/filter beans behind the editor's list pages:
 * {@link EntriesBean} and {@link MediaFileSearchBean}.
 *
 * <p>These are pure translation layers from form fields to query criteria, with
 * no business tier behind them — which makes them cheap to test exhaustively
 * and easy to get subtly wrong. A mis-mapped filter silently returns the wrong
 * rows rather than failing, so every branch of the mapping is pinned here.
 */
class QueryBeansTest {

    @Nested
    class EntriesBeanTest {

        @Test
        void datesAreParsedFromTheEditorsUsFormat() {
            EntriesBean bean = new EntriesBean();
            bean.setStartDateString("03/15/24");
            bean.setEndDateString("04/20/24");

            assertEquals("2024-03-15", format(bean.getStartDate()));
            assertEquals("2024-04-20", format(bean.getEndDate()));
        }

        @Test
        void anAbsentOrUnparseableDateBecomesNoBoundRatherThanAnError() {
            // A null bound means "unbounded" to the search criteria; throwing
            // here would break the list page on a typo.
            EntriesBean bean = new EntriesBean();
            assertNull(bean.getStartDate(), "No date typed means no lower bound");
            assertNull(bean.getEndDate(), "No date typed means no upper bound");

            bean.setStartDateString("");
            bean.setEndDateString("");
            assertNull(bean.getStartDate());
            assertNull(bean.getEndDate());

            bean.setStartDateString("garbage");
            bean.setEndDateString("garbage");
            assertNull(bean.getStartDate(), "A typo must not become a bogus bound");
            assertNull(bean.getEndDate());
        }

        @Test
        void tagsAreSplitOnWhitespace() {
            EntriesBean bean = new EntriesBean();
            bean.setTagsAsString("travel  food   ");

            assertEquals(List.of("travel", "food"), bean.getTags());
        }

        @Test
        void noTagFilterIsAnEmptyListRatherThanNull() {
            // getTags() used to return null here, forcing its one caller
            // (EntriesController, which feeds it straight into
            // WeblogEntrySearchCriteria.setTags) to carry a null check on a
            // collection. JPAWeblogEntryManagerImpl.getWeblogEntries already
            // treats "tags == null" and "tags.isEmpty()" identically -- both
            // skip the tag join entirely -- so an empty list means exactly
            // what null used to mean: no tag filter at all.
            assertEquals(List.of(), new EntriesBean().getTags());
        }

        @Test
        void theListDefaultsToEverythingSortedByMostRecentlyEdited() {
            EntriesBean bean = new EntriesBean();

            assertEquals("ALL", bean.getStatus(),
                    "The management list must show drafts and pending entries by default, "
                            + "since those are what the author still has to act on");
            assertEquals(org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria.SortBy.UPDATE_TIME,
                    bean.getSortBy());
            assertEquals(0, bean.getPage());
        }
    }

    @Nested
    class MediaFileSearchBeanTest {

        @Test
        void eachFileTypeOptionMapsToItsMediaFileType() {
            assertEquals(MediaFileType.AUDIO, typeFor("mediaFileView.audio"));
            assertEquals(MediaFileType.VIDEO, typeFor("mediaFileView.video"));
            assertEquals(MediaFileType.IMAGE, typeFor("mediaFileView.image"));
            assertEquals(MediaFileType.OTHERS, typeFor("mediaFileView.others"));
        }

        @Test
        void theAnyOptionAppliesNoTypeFilter() {
            // "any" is a real option in the dropdown and must not be mapped to
            // a concrete type, which would hide everything else.
            assertNull(typeFor("mediaFileView.any"));
        }

        @Test
        void noTypeChosenLeavesTheFilterUntouched() {
            MediaFileFilter filter = new MediaFileFilter();
            filter.setType(MediaFileType.IMAGE);
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setType("");

            bean.copyTo(filter);

            assertEquals(MediaFileType.IMAGE, filter.getType(),
                    "An empty type must leave any pre-existing filter alone rather than "
                            + "clearing it");
        }

        @Test
        void eachSizeComparisonMapsToItsFilterType() {
            assertEquals(SizeFilterType.GT, sizeFilterFor("mediaFileView.gt"));
            assertEquals(SizeFilterType.GTE, sizeFilterFor("mediaFileView.ge"));
            assertEquals(SizeFilterType.EQ, sizeFilterFor("mediaFileView.eq"));
            assertEquals(SizeFilterType.LTE, sizeFilterFor("mediaFileView.le"));
            assertEquals(SizeFilterType.LT, sizeFilterFor("mediaFileView.lt"));
        }

        @Test
        void anUnrecognisedSizeComparisonFallsBackToEquals() {
            assertEquals(SizeFilterType.EQ, sizeFilterFor("nonsense"));
        }

        @Test
        void sizeUnitsAreConvertedToBytes() {
            // The filter is applied against a byte column; forgetting the
            // conversion makes "larger than 1 MB" mean "larger than 1 byte".
            assertEquals(5L, sizeInBytes(5, "mediaFileView.bytes"));
            assertEquals(5L * RollerConstants.ONE_KB_IN_BYTES, sizeInBytes(5, "mediaFileView.kb"));
            assertEquals(5L * RollerConstants.ONE_MB_IN_BYTES, sizeInBytes(5, "mediaFileView.mb"));
        }

        @Test
        void anUnrecognisedSizeUnitIsTreatedAsBytes() {
            assertEquals(5L, sizeInBytes(5, "nonsense"));
        }

        @Test
        void aSizeOfZeroMeansNoSizeFilterAtAll() {
            // Zero is the "not filled in" value for the numeric field; treating
            // it as a real filter would match nothing.
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(0);
            bean.setSizeFilterType("mediaFileView.gt");

            bean.copyTo(filter);

            assertNull(filter.getSizeFilterType());
        }

        @Test
        void tagsAreSplitOnSpaces() {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setTags("holiday beach");

            bean.copyTo(filter);

            assertEquals(List.of("holiday", "beach"), filter.getTags());
        }

        @Test
        void noTagsLeavesTheTagFilterUnset() {
            MediaFileFilter filter = new MediaFileFilter();
            new MediaFileSearchBean().copyTo(filter);

            assertNull(filter.getTags());
        }

        @Test
        void pagingAsksForOneRowMoreThanAPageSoTheUiCanTellThereIsANextPage() {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setPageNum(3);

            bean.copyTo(filter);

            assertEquals(3 * MediaFileSearchBean.PAGE_SIZE, filter.getStartIndex());
            assertEquals(MediaFileSearchBean.PAGE_SIZE + 1, filter.getLength(),
                    "The lookahead row is how the pager knows whether to show 'next'");
        }

        @Test
        void eachSortOptionMapsToItsOrdering() {
            assertEquals(MediaFileOrder.NAME, orderFor(0));
            assertEquals(MediaFileOrder.DATE_UPLOADED, orderFor(1));
            assertEquals(MediaFileOrder.TYPE, orderFor(2));
        }

        @Test
        void anUnrecognisedSortOptionLeavesTheOrderingToTheManager() {
            assertNull(orderFor(99));
        }

        private MediaFileType typeFor(String option) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setType(option);
            bean.copyTo(filter);
            return filter.getType();
        }

        private SizeFilterType sizeFilterFor(String option) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(10);
            bean.setSizeFilterType(option);
            bean.copyTo(filter);
            return filter.getSizeFilterType();
        }

        private long sizeInBytes(long size, String unit) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(size);
            bean.setSizeUnit(unit);
            bean.copyTo(filter);
            return filter.getSize();
        }

        private MediaFileOrder orderFor(int sortOption) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSortOption(sortOption);
            bean.copyTo(filter);
            return filter.getOrder();
        }
    }

    private static String format(Date date) {
        assertTrue(date != null, "Expected a parsed date");
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date);
    }
}
