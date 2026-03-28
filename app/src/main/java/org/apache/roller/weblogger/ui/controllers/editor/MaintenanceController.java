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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Allows user to perform maintenance operations such as flushing the page cache
 * or re-indexing the search index.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class MaintenanceController extends BaseController {

    private static final Log log = LogFactory.getLog(MaintenanceController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "maintenance";
    }

    @Override
    public String getPageTitle() {
        return "maintenance.title";
    }

    @GetMapping("/maintenance.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return ".Maintenance";
    }

    @PostMapping("/maintenance!flushCache.rol")
    public String flushCache(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            Weblog weblog = getActionWeblog(request);
            weblog.setLastModified(new Date());
            WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(weblog);
            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(weblog);
            addMessage(model, "maintenance.message.flushed", request);
        } catch (Exception ex) {
            log.error("Error saving weblog - " + getActionWeblog(request).getHandle(), ex);
            addError(model, "Error flushing page cache", request);
        }

        return ".Maintenance";
    }

    @PostMapping("/maintenance!reset.rol")
    public String reset(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            Weblog weblog = getActionWeblog(request);
            WeblogEntryManager mgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            mgr.resetHitCount(weblog);
            weblog.setLastModified(new Date());
            WebloggerFactory.getWeblogger().getWeblogManager().saveWeblog(weblog);
            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(weblog);
            addMessage(model, "maintenance.message.reset", request);
        } catch (Exception ex) {
            log.error("Error saving weblog - " + getActionWeblog(request).getHandle(), ex);
            addError(model, "Error flushing page cache", request);
        }

        return ".Maintenance";
    }
}
