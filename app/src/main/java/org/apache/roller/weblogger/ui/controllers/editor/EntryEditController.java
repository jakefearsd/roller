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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.DateUtil;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.plugins.UIPluginManager;
import org.apache.roller.weblogger.ui.core.plugins.WeblogEntryEditor;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.MailUtil;
import org.apache.roller.weblogger.util.MediacastException;
import org.apache.roller.weblogger.util.MediacastResource;
import org.apache.roller.weblogger.util.MediacastUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Edit a new or existing entry.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntryEditController extends BaseController {

    private static final Log log = LogFactory.getLog(EntryEditController.class);

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- entryAdd ---

    @GetMapping("/entryAdd.rol")
    public String entryAddExecute(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryAdd");
        model.addAttribute("pageTitle", getText("weblogEdit.title.newEntry", request));

        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        entry.setWebsite(getActionWeblog(request));

        // set weblog defaults
        bean.setLocale(getActionWeblog(request).getLocale());
        bean.setAllowComments(getActionWeblog(request).getDefaultAllowComments());
        bean.setCommentDays(getActionWeblog(request).getDefaultCommentDays());
        if (getActionWeblog(request).getDefaultPlugins() != null) {
            bean.setPlugins(StringUtils.split(getActionWeblog(request).getDefaultPlugins(), ","));
        }

        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry);
        return ".EntryEdit";
    }

    @PostMapping("/entryAdd!saveDraft.rol")
    public String entryAddSaveDraft(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") EntryBean bean) {
        return doEntryAddSave(request, model, bean, "saveDraft");
    }

    @PostMapping("/entryAdd!publish.rol")
    public String entryAddPublish(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") EntryBean bean) {
        return doEntryAddSave(request, model, bean, "publish");
    }

    private String doEntryAddSave(HttpServletRequest request, Model model, EntryBean bean, String action) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryAdd");
        model.addAttribute("pageTitle", getText("weblogEdit.title.newEntry", request));

        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        entry.setWebsite(getActionWeblog(request));

        if ("saveDraft".equals(action)) {
            bean.setStatus(PubStatus.DRAFT.name());
        } else if ("publish".equals(action)) {
            setPublishStatus(bean, entry, request);
        }

        String result = doSave(request, model, bean, entry, "entryAdd");
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry);
        return result;
    }

    // --- entryEdit ---

    @GetMapping("/entryEdit.rol")
    public String entryEditExecute(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId());
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry);
        return ".EntryEdit";
    }

    @GetMapping("/entryEdit!firstSave.rol")
    public String entryEditFirstSave(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId());
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        addStatusMessage(entry.getStatus(), model, entry, request);
        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry);
        return ".EntryEdit";
    }

    @PostMapping("/entryEdit!saveDraft.rol")
    public String entryEditSaveDraft(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean) {
        return doEntryEditSave(request, model, bean, "saveDraft");
    }

    @PostMapping("/entryEdit!publish.rol")
    public String entryEditPublish(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean) {
        return doEntryEditSave(request, model, bean, "publish");
    }

    private String doEntryEditSave(HttpServletRequest request, Model model, EntryBean bean, String action) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId());
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        if ("saveDraft".equals(action)) {
            bean.setStatus(PubStatus.DRAFT.name());
            if (entry.isPublished()) {
                entry.setRefreshAggregates(true);
            }
        } else if ("publish".equals(action)) {
            setPublishStatus(bean, entry, request);
        }

        String result = doSave(request, model, bean, entry, "entryEdit");
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry);
        return result;
    }

    private void setPublishStatus(EntryBean bean, WeblogEntry entry, HttpServletRequest request) {
        if (getActionWeblog(request).hasUserPermission(
                getAuthenticatedUser(request), WeblogPermission.POST)) {
            Timestamp pubTime = bean.getPubTime(request.getLocale(),
                    getActionWeblog(request).getTimeZoneInstance());
            if (pubTime != null && pubTime.after(
                    new Date(System.currentTimeMillis() + RollerConstants.MIN_IN_MS))) {
                bean.setStatus(PubStatus.SCHEDULED.name());
                if (entry.isPublished()) {
                    entry.setRefreshAggregates(true);
                }
            } else {
                bean.setStatus(PubStatus.PUBLISHED.name());
                if (bean.getId() != null && !entry.isPublished()) {
                    entry.setRefreshAggregates(true);
                }
            }
        } else {
            bean.setStatus(PubStatus.PENDING.name());
        }
    }

    private String doSave(HttpServletRequest request, Model model, EntryBean bean,
                          WeblogEntry entry, String actionName) {
        if (!hasErrors(model)) {
            try {
                WeblogEntryManager weblogEntryManager = WebloggerFactory.getWeblogger().getWeblogEntryManager();
                IndexManager indexMgr = WebloggerFactory.getWeblogger().getIndexManager();

                entry.setUpdateTime(new Timestamp(new Date().getTime()));
                entry.setPubTime(bean.getPubTime(request.getLocale(),
                        getActionWeblog(request).getTimeZoneInstance()));

                bean.copyTo(entry);

                if (entry.isPublished() && entry.getPubTime() == null) {
                    entry.setPubTime(entry.getUpdateTime());
                }

                GlobalPermission adminPerm = new GlobalPermission(
                        Collections.singletonList(GlobalPermission.ADMIN));
                if (WebloggerFactory.getWeblogger().getUserManager()
                        .checkPermission(adminPerm, getAuthenticatedUser(request))) {
                    entry.setPinnedToMain(bean.getPinnedToMain());
                }

                if (!StringUtils.isEmpty(bean.getEnclosureURL())) {
                    try {
                        MediacastResource mediacast = MediacastUtil.lookupResource(bean.getEnclosureURL());
                        entry.putEntryAttribute("att_mediacast_url", mediacast.getUrl());
                        entry.putEntryAttribute("att_mediacast_type", mediacast.getContentType());
                        entry.putEntryAttribute("att_mediacast_length", "" + mediacast.getLength());
                    } catch (MediacastException ex) {
                        addMessage(model, ex.getErrorKey(), request);
                    }
                } else if ("entryEdit".equals(actionName)) {
                    try {
                        weblogEntryManager.removeWeblogEntryAttribute("att_mediacast_url", entry);
                        weblogEntryManager.removeWeblogEntryAttribute("att_mediacast_type", entry);
                        weblogEntryManager.removeWeblogEntryAttribute("att_mediacast_length", entry);
                    } catch (WebloggerException e) {
                        addMessage(model, "weblogEdit.mediaCastErrorRemoving", request);
                    }
                }

                weblogEntryManager.saveWeblogEntry(entry);
                WebloggerFactory.getWeblogger().flush();

                if (entry.isPublished()) {
                    indexMgr.addEntryReIndexOperation(entry);
                } else if ("entryEdit".equals(actionName)) {
                    indexMgr.removeEntryIndexOperation(entry);
                }

                CacheManager.invalidate(entry);

                if (entry.isPending() && MailUtil.isMailConfigured()) {
                    MailUtil.sendPendingEntryNotice(entry);
                }

                if ("entryEdit".equals(actionName)) {
                    addStatusMessage(entry.getStatus(), model, entry, request);
                    return ".EntryEdit";
                } else {
                    bean.setId(entry.getId());
                    return "redirect:/roller-ui/authoring/entryEdit!firstSave.rol?weblog="
                            + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
                }

            } catch (Exception e) {
                log.error("Error saving new entry", e);
                addError(model, "generic.error.check.logs", request);
            }
        }
        if ("entryAdd".equals(actionName)) {
            bean.setStatus(null);
        }
        return ".EntryEdit";
    }

    private void addStatusMessage(PubStatus pubStatus, Model model, WeblogEntry entry, HttpServletRequest request) {
        switch (pubStatus) {
            case DRAFT:
                addMessage(model, "weblogEdit.draftSaved", request);
                break;
            case PUBLISHED:
                addMessage(model, "weblogEdit.publishedEntry", request);
                break;
            case SCHEDULED:
                addMessage(model, "weblogEdit.scheduledEntry",
                        DateUtil.fullDate(entry.getPubTime()), request);
                break;
            case PENDING:
                addMessage(model, "weblogEdit.submittedForReview", request);
                break;
        }
    }

    private WeblogEntry lookupEntry(String id) {
        if (id == null) {
            return null;
        }
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            return wmgr.getWeblogEntry(id);
        } catch (WebloggerException ex) {
            log.error("Error looking up entry by id - " + id, ex);
        }
        return null;
    }

    private void addEntryModelAttributes(HttpServletRequest request, Model model, WeblogEntry entry) {
        model.addAttribute("categories", getCategories(request));
        model.addAttribute("entryPlugins", getEntryPlugins(request));
        model.addAttribute("editor", getEditor(request));
        model.addAttribute("userAnAuthor", getActionWeblog(request).hasUserPermission(
                getAuthenticatedUser(request), WeblogPermission.POST));
        model.addAttribute("jsonAutocompleteUrl", WebloggerFactory.getWeblogger().getUrlStrategy()
                .getWeblogTagsJsonURL(getActionWeblog(request), false, 0));

        if (entry.getId() != null) {
            model.addAttribute("previewURL", WebloggerFactory.getWeblogger().getUrlStrategy()
                    .getPreviewURLStrategy(null)
                    .getWeblogEntryURL(getActionWeblog(request), null, entry.getAnchor(), true));
        }

        // Hour/minute/second lists for pub time selectors
        List<Integer> hoursList = new ArrayList<>();
        for (int i = 0; i < 24; i++) hoursList.add(i);
        model.addAttribute("hoursList", hoursList);

        List<Integer> minutesList = new ArrayList<>();
        for (int i = 0; i < 60; i++) minutesList.add(i);
        model.addAttribute("minutesList", minutesList);
        model.addAttribute("secondsList", new ArrayList<>(minutesList));

        // Locale list for multi-language blogs
        model.addAttribute("localesList", org.apache.roller.weblogger.ui.controllers.util.UIUtils.getLocales());

        // Comment days options
        model.addAttribute("commentDaysList", getCommentDaysList(request));

        model.addAttribute("recentPublishedEntries",
                getRecentEntries(request, PubStatus.PUBLISHED, WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME));
        model.addAttribute("recentScheduledEntries",
                getRecentEntries(request, PubStatus.SCHEDULED, WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME));
        model.addAttribute("recentDraftEntries",
                getRecentEntries(request, PubStatus.DRAFT, WeblogEntrySearchCriteria.SortBy.UPDATE_TIME));
        model.addAttribute("recentPendingEntries",
                getRecentEntries(request, PubStatus.PENDING, WeblogEntrySearchCriteria.SortBy.UPDATE_TIME));
    }

    private Map<Integer, String> getCommentDaysList(HttpServletRequest request) {
        Map<Integer, String> map = new LinkedHashMap<>();
        map.put(0, getText("weblogEdit.unlimitedCommentDays", request));
        map.put(1, getText("weblogEdit.days1", request));
        map.put(2, getText("weblogEdit.days2", request));
        map.put(3, getText("weblogEdit.days3", request));
        map.put(7, getText("weblogEdit.days7", request));
        map.put(14, getText("weblogEdit.days14", request));
        map.put(30, getText("weblogEdit.days30", request));
        map.put(60, getText("weblogEdit.days60", request));
        map.put(90, getText("weblogEdit.days90", request));
        map.put(-1, getText("weblogEdit.noComments", request));
        return map;
    }

    private List<WeblogCategory> getCategories(HttpServletRequest request) {
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            return wmgr.getWeblogCategories(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error getting category list", ex);
            return Collections.emptyList();
        }
    }

    private List<WeblogEntryPlugin> getEntryPlugins(HttpServletRequest request) {
        try {
            PluginManager ppmgr = WebloggerFactory.getWeblogger().getPluginManager();
            Map<String, WeblogEntryPlugin> plugins = ppmgr.getWeblogEntryPlugins(getActionWeblog(request));
            if (!plugins.isEmpty()) {
                return new ArrayList<>(plugins.values());
            }
        } catch (Exception ex) {
            log.error("Error getting plugins list", ex);
        }
        return Collections.emptyList();
    }

    private WeblogEntryEditor getEditor(HttpServletRequest request) {
        UIPluginManager pmgr = RollerContext.getUIPluginManager();
        return pmgr.getWeblogEntryEditor(getActionWeblog(request).getEditorPage());
    }

    private List<WeblogEntry> getRecentEntries(HttpServletRequest request, PubStatus pubStatus,
                                               WeblogEntrySearchCriteria.SortBy sortBy) {
        try {
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(getActionWeblog(request));
            wesc.setMaxResults(20);
            wesc.setStatus(pubStatus);
            wesc.setSortBy(sortBy);
            return WebloggerFactory.getWeblogger().getWeblogEntryManager().getWeblogEntries(wesc);
        } catch (WebloggerException ex) {
            log.error("Error getting entries list", ex);
        }
        return Collections.emptyList();
    }

    @ModelAttribute("bean")
    public EntryBean getBean() {
        return new EntryBean();
    }
}
