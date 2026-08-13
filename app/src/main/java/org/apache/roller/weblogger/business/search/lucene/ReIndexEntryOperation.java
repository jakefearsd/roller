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
/* Created on Jul 16, 2003 */
package org.apache.roller.weblogger.business.search.lucene;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * An operation that adds a new log entry into the index.
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class ReIndexEntryOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static Log logger = LogFactory.getFactory().getInstance(
            AddEntryOperation.class);

    // ~ Instance fields
    // ========================================================

    private WeblogEntry data;
    private Weblogger roller;

    // ~ Constructors
    // ===========================================================

    /**
     * Adds a web log entry into the index.
     */
    public ReIndexEntryOperation(Weblogger roller, LuceneIndexManager mgr,
            WeblogEntry data) {
        super(mgr);
        this.roller = roller;
        this.data = data;
    }

    // ~ Methods
    // ================================================================

    @Override
    public void doRun() {

        // since this operation can be run on a separate thread we must treat
        // the weblog object passed in as a detached object which is prone to
        // lazy initialization problems, so requery for the object now.
        // The id is captured before the requery because a genuinely deleted
        // entry re-fetches as null, and the delete-by-id below still needs
        // to know what id to remove from the index.
        String entryId = this.data.getId();
        try {
            WeblogEntryManager wMgr = roller.getWeblogEntryManager();
            this.data = wMgr.getWeblogEntry(entryId);
        } catch (WebloggerException ex) {
            logger.error("Error getting weblogentry object", ex);
            return;
        }

        IndexWriter writer = beginWriting();
        try {
            if (writer != null) {

                // Delete Doc -- always, whether or not it comes back below.
                // A re-fetch that returns null (entry deleted out from under
                // this queued job) or a non-PUBLISHED entry (e.g. trashed
                // since this job was queued) must not leave a stale document
                // behind.
                Term term = new Term(FieldConstants.ID, entryId);
                writer.deleteDocuments(term);

                // Add Doc -- only a published entry belongs in the search
                // index. Every caller already gates on isPublished() before
                // scheduling this operation, but by the time this runs on
                // its own thread the entry may have moved on (trashed,
                // unpublished, deleted) -- that check is enforced here too
                // rather than trusted from the scheduling side.
                if (data != null && data.isPublished()) {
                    writer.addDocument(getDocument(data));
                }
            }
        } catch (IOException e) {
            logger.error("Problems adding/deleting doc to index", e);
        } finally {
            if (roller != null) {
                roller.release();
            }
            endWriting();
        }
    }
}
