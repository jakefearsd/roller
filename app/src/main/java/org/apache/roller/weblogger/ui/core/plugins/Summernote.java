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

package org.apache.roller.weblogger.ui.core.plugins;

/**
 * Summernote rich text editor
 */
public class Summernote implements WeblogEntryEditor {


    public Summernote() {}
    
    
    @Override
    public String getId() {
        // Was "editor-xinha.jsp", left over from the Xinha editor this class
        // replaced. roller.properties sets plugins.defaultEditor to
        // editor-summernote.jsp, so the lookup in UIPluginManagerImpl missed,
        // logged "Default editor was not properly configured", and fell back to
        // the first entry of a HashMap - meaning which editor an author got was
        // effectively arbitrary, and often the plain textarea rather than the
        // rich one.
        return "editor-summernote.jsp";
    }
    
    @Override
    public String getName() {
        return "editor.summernote.name";
    }
    
    @Override
    public String getJspPage() {
        return "/WEB-INF/jsps/editor/EntryEditor.jsp";
    }
    
}
