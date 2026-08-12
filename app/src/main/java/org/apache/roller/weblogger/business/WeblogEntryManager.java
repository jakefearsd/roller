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

package org.apache.roller.weblogger.business;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.StatCount;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;


/**
 * Interface to weblog entry and category management.
 */
public interface WeblogEntryManager {

    /**
     * Save weblog entry.
     */
    void saveWeblogEntry(WeblogEntry entry) throws WebloggerException;
       
    /**
     * Remove weblog entry.
     */
    void removeWeblogEntry(WeblogEntry entry) throws WebloggerException;
    
    /**
     * Get weblog entry by id.
     */
    WeblogEntry getWeblogEntry(String id) throws WebloggerException;

    /**
     * This entry's saved revisions, newest first.
     *
     * <p>Each holds the entry's content as it stood BEFORE one save, so the
     * first element is what an author would get back by undoing their most
     * recent save.
     */
    List<WeblogEntryRevision> getRevisions(WeblogEntry entry) throws WebloggerException;

    /**
     * One revision by id, or null when there is no such revision.
     *
     * <p>Callers must still check that the revision belongs to an entry the
     * caller may edit: the id arrives as client input, exactly like an entry
     * id does.
     */
    WeblogEntryRevision getRevision(String id) throws WebloggerException;
    
    /** 
     * Get weblog entry by anchor. 
     */
    WeblogEntry getWeblogEntryByAnchor(Weblog website, String anchor)
            throws WebloggerException;
        
    /**
     * Get WeblogEntries by offset/length as list in reverse chronological order.
     * The range offset and list arguments enable paging through query results.
     * @param wesc WeblogEntrySearchCriteria object listing desired search parameters
     * @return List of WeblogEntry objects in order specified by search criteria
     * @throws WebloggerException
     */
    List<WeblogEntry> getWeblogEntries(WeblogEntrySearchCriteria wesc)
            throws WebloggerException;

    /**
     * Get Weblog Entries grouped by day.
     * @param wesc WeblogEntrySearchCriteria object listing desired search parameters
     * @return Map of Lists of WeblogEntries keyed by calendar day
     * @throws WebloggerException
     */
    Map<Date, List<WeblogEntry>> getWeblogEntryObjectMap(WeblogEntrySearchCriteria wesc)
            throws WebloggerException;

    /**
     * Get Weblog Entry date strings grouped by day. This method returns a Map
     * that contains one YYYYMMDD date string object for each calendar day having
     * one or more blog entries.
     * @param wesc WeblogEntrySearchCriteria object listing desired search parameters
     * @return Map of date strings keyed by Date
     * @throws WebloggerException
     */
    Map<Date, String> getWeblogEntryStringMap(WeblogEntrySearchCriteria wesc)
            throws WebloggerException;
    
    /**
     * Get the WeblogEntry following, chronologically, the current entry.
     * Restrict by the Category, if named.
     * @param current The "current" WeblogEntryData
     * @param catName The value of the requested Category Name
     */
    WeblogEntry getNextEntry(WeblogEntry current,
            String catName, String locale) throws WebloggerException;    
    
    /**
     * Get the WeblogEntry prior to, chronologically, the current entry.
     * Restrict by the Category, if named.
     * @param current The "current" WeblogEntryData.
     * @param catName The value of the requested Category Name.
     */
    WeblogEntry getPreviousEntry(WeblogEntry current,
            String catName, String locale) throws WebloggerException;
      
    
    /**
     * Get specified number of most recent pinned and published Weblog Entries.
     * @param max Maximum number to return.
     * @return Collection of WeblogEntry objects.
     */
    List<WeblogEntry> getWeblogEntriesPinnedToMain(Integer max) throws WebloggerException;

    /**
     * Remove attribute with given name from given WeblogEntryData
     * @param name Name of attribute to be removed
     */
    void removeWeblogEntryAttribute(String name,WeblogEntry entry)
            throws WebloggerException;

    /**
     * Save weblog category.
     */
    void saveWeblogCategory(WeblogCategory cat) throws WebloggerException;
    
    /**
     * Remove weblog category.
     */
    void removeWeblogCategory(WeblogCategory cat) throws WebloggerException;
        
    /**
     * Get category by id.
     */
    WeblogCategory getWeblogCategory(String id) throws WebloggerException;
    
    
    /**
     * Recategorize all entries with one category to another.
     */
    void moveWeblogCategoryContents(WeblogCategory srcCat, WeblogCategory destCat)
            throws WebloggerException;
    
    /**
     * Get category specified by website and name.
     * @param website      Website of WeblogCategory.
     * @param categoryName Name of WeblogCategory
     */
    WeblogCategory getWeblogCategoryByName(Weblog website,
            String categoryName) throws WebloggerException;

    /**
     * Get WebLogCategory objects for a website. 
     */
    List<WeblogCategory> getWeblogCategories(Weblog website)
            throws WebloggerException;

    /**
     * Create unique anchor for weblog entry.
     */
    String createAnchor(WeblogEntry data) throws WebloggerException;
    
    /**
     * Check for duplicate category name.
     */
    boolean isDuplicateWeblogCategoryName(WeblogCategory data)
            throws WebloggerException;  
    
    /**
     * Check if weblog category is in use.
     */
    boolean isWeblogCategoryInUse(WeblogCategory data)
            throws WebloggerException;


    /**
     * Release all resources held by manager.
     */
    void release();
    
    /**
     * Get list of TagStat. There's no offset/length params just a limit.
     * @param website       Weblog or null to get for all weblogs.
     * @param startDate     Date or null of the most recent time a tag was used.
     * @param limit         Max TagStats to return (or -1 for no limit)
     * @return List of most popular tags.
     * @throws WebloggerException
     */
    List<TagStat> getPopularTags(Weblog website, Date startDate, int offset, int limit)
            throws WebloggerException;
    
    /**
     * Get list of TagStat. There's no offset/length params just a limit.
     * @param website       Weblog or null to get for all weblogs.
     * @param sortBy        Sort by either 'name' or 'count' (null for name) 
     * @param startsWith    Prefix for tags to be returned (null or a string of length > 0)
     * @param limit         Max TagStats to return (or -1 for no limit)
     * @return List of tags matching the criteria.
     * @throws WebloggerException
     */
    List<TagStat> getTags(Weblog website, String sortBy, String startsWith, int offset, int limit)
            throws WebloggerException;    
    
    /**
     * Does the specified tag combination exist?  Optionally confined to a specific weblog.
     *
     * This tests if the intersection of the tags listed will yield any results
     * and returns a true/false value if so.  This means that if the tags list
     * is "foo", "bar" and only the tag "foo" has been used then this method
     * should return false.
     *
     * @param tags The List of tags to check for.
     * @param weblog The weblog to confine the check to.
     * @return True if tags exist, false otherwise.
     * @throws WebloggerException If there is any problem doing the operation.
     */
    boolean getTagComboExists(List<String> tags, Weblog weblog) throws WebloggerException;

    /**
     * Get site-wide entry count
     */
    long getEntryCount() throws WebloggerException;

    
    /**
     * Get weblog entry count 
     */    
    long getEntryCount(Weblog websiteData) throws WebloggerException;

}

