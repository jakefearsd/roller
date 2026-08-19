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
import java.util.Locale;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.util.BytesRef;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * This is the base class for all index operation. These operations include:<br>
 * SearchOperation<br>
 * AddWeblogOperation<br>
 * RemoveWeblogOperation<br>
 * RebuildUserIndexOperation
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public abstract class IndexOperation implements Runnable {

    private static Log logger = LogFactory.getFactory().getInstance(
            IndexOperation.class);

    // ~ Instance fields
    // ========================================================
    protected LuceneIndexManager manager;
    private IndexWriter writer;
    // Must stay open for the IndexWriter's whole lifetime -- IndexWriterConfig
    // only holds a reference to it, and Lucene re-uses it on every add/commit.
    // Closed alongside the writer in endWriting(), never earlier.
    private LimitTokenCountAnalyzer analyzer;

    // ~ Constructors
    // ===========================================================
    public IndexOperation(LuceneIndexManager manager) {
        this.manager = manager;
    }

    // ~ Methods
    // ================================================================
    protected Document getDocument(WeblogEntry data) {

        Document doc = new Document();

        // keyword
        doc.add(new StringField(FieldConstants.ID, data.getId(),
                Field.Store.YES));

        // keyword
        doc.add(new StringField(FieldConstants.WEBSITE_HANDLE, data
                .getWebsite().getHandle(), Field.Store.YES));

        // text, don't index deleted/disabled users of a group blog
        if (data.getCreator() != null) {
            doc.add(new TextField(FieldConstants.USERNAME, data.getCreator()
                    .getUserName().toLowerCase(Locale.ROOT), Field.Store.YES));
        }

        // text
        doc.add(new TextField(FieldConstants.TITLE, data.getTitle(),
                Field.Store.YES));

        // keyword needs to be in lower case as we are used in a term
        doc.add(new StringField(FieldConstants.LOCALE, data.getLocale()
                .toLowerCase(Locale.ROOT), Field.Store.YES));

        // index the entry text, but don't store it
        doc.add(new TextField(FieldConstants.CONTENT, data.getText(),
                Field.Store.NO));

        // keyword
        doc.add(new StringField(FieldConstants.UPDATED, data.getUpdateTime()
                .toString(), Field.Store.YES));

        // keyword
        if (data.getPubTime() != null) {
            // SearchOperation sorts results by date
            doc.add(new SortedDocValuesField(FieldConstants.PUBLISHED, new BytesRef(data.getPubTime().toString())));
        }

        // index Category, needs to be in lower case as it is used in a term
        WeblogCategory categorydata = data.getCategory();
        if (categorydata != null) {
            doc.add(new StringField(FieldConstants.CATEGORY, categorydata
                    .getName().toLowerCase(Locale.ROOT), Field.Store.YES));
        }

        return doc;
    }

    /**
     * Begin writing.
     * 
     * @return the index writer
     */
    protected IndexWriter beginWriting() {
        try {

            analyzer = new LimitTokenCountAnalyzer(
                    LuceneIndexManager.getAnalyzer(),
                    WebloggerConfig.getIntProperty("lucene.analyzer.maxTokenCount"));

            IndexWriterConfig config = new IndexWriterConfig(analyzer);

            writer = new IndexWriter(manager.getIndexDirectory(), config);

        } catch (IOException e) {
            logger.error("ERROR creating writer", e);
        }

        return writer;
    }

    /**
     * End writing.
     */
    protected void endWriting() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                logger.error("ERROR closing writer", e);
            }
        }
        // Close only after the writer is fully closed above -- the writer
        // uses the analyzer during its own close()/commit.
        if (analyzer != null) {
            analyzer.close();
            analyzer = null;
        }
    }

    /**
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        doRun();
    }

    protected abstract void doRun();
}
