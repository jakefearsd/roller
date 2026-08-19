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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.ui.controllers.pagers.EntriesPager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A list view of entries in a weblog.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntriesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntriesController.class);
    private static final int COUNT = 30;

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "entries";
    }

    @Override
    public String getPageTitle() {
        return "weblogEntryQuery.title";
    }

    @GetMapping("/entries.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") EntriesBean bean) {
        populateCommonModel(request, model);

        List<WeblogEntry> entries = null;
        boolean hasMore = false;
        try {
            String status = bean.getStatus();
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(getActionWeblog(request));
            wesc.setStartDate(bean.getStartDate());
            wesc.setEndDate(bean.getEndDate());
            wesc.setCatName(bean.getCategoryName());
            wesc.setTags(bean.getTags());
            wesc.setStatus("ALL".equals(status) ? null : WeblogEntry.PubStatus.valueOf(status));
            wesc.setText(bean.getText());
            wesc.setSortBy(bean.getSortBy());
            wesc.setOffset(bean.getPage() * COUNT);
            wesc.setMaxResults(COUNT + 1);
            List<WeblogEntry> rawEntries = wmgr.getWeblogEntries(wesc);
            entries = new ArrayList<>(rawEntries);
            if (!entries.isEmpty()) {
                if (rawEntries.size() > COUNT) {
                    entries.remove(entries.size() - 1);
                    hasMore = true;
                }
                model.addAttribute("firstEntry", entries.get(0));
                model.addAttribute("lastEntry", entries.get(entries.size() - 1));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up entries", ex);
            addError(model, "Error looking up entries", request);
        }

        String baseUrl = buildBaseUrl(request, bean);
        model.addAttribute("pager", new EntriesPager(baseUrl, bean.getPage(), entries, hasMore));
        model.addAttribute("categories", getCategories(request));
        model.addAttribute("sortByOptions", getSortByOptions(request));
        model.addAttribute("statusOptions", getStatusOptions(request));

        return ".Entries";
    }

    /**
     * Copies an entry and opens the copy in the editor.
     *
     * <p>The copy is a draft with a fresh id and no anchor of its own (see
     * {@link WeblogEntry#copyAsDraft}), so nothing about the original changes
     * and nothing new appears on the blog. The redirect goes to the editor
     * rather than back to the list, because an author duplicates a post in
     * order to edit it, and a draft named "Copy of ..." sitting in the list is
     * not a finished outcome.
     */
    @PostMapping("/entries!duplicate.rol")
    public String duplicate(HttpServletRequest request,
                            @RequestParam(value = "duplicateId", required = false) String duplicateId,
                            RedirectAttributes redirectAttributes) {
        String handle = getActionWeblog(request).getHandle();
        String backToList = "redirect:/roller-ui/authoring/entries.rol?weblog=" + handle;

        // lookupNonTrashedEntry, not lookupEntry: a forged duplicateId
        // naming a trashed entry must not be usable to duplicate it back
        // into a live draft -- that is what restore is for.
        WeblogEntry original = lookupNonTrashedEntry(duplicateId, request);
        if (original == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return backToList;
        }

        try {
            WeblogEntry copy = original.copyAsDraft(getText("weblogEdit.duplicateTitle",
                    new Object[] {original.getTitle()}, request));
            weblogger.getWeblogEntryManager().saveWeblogEntry(copy);
            weblogger.flush();

            addFlashMessage(redirectAttributes, "weblogEdit.entryDuplicated",
                    copy.getTitle(), request);
            return "redirect:/roller-ui/authoring/entryEdit.rol?weblog=" + handle
                    + "&bean.id=" + copy.getId();
        } catch (Exception e) {
            log.error("Error duplicating entry {}", duplicateId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
            return backToList;
        }
    }

    // ---------------------------------------------------------- bulk actions

    /**
     * Publishes every selected entry.
     *
     * <p>An author without POST permission can only submit for review, exactly
     * as the editor's own publish button does -- a bulk action must not be a
     * way around a permission the single-entry path enforces.
     */
    @PostMapping("/entries!bulkPublish.rol")
    public String bulkPublish(HttpServletRequest request,
                              @RequestParam(value = "selectedEntries", required = false)
                              List<String> selectedEntries,
                              RedirectAttributes redirectAttributes) {
        boolean mayPublish = getActionWeblog(request)
                .hasUserPermission(getAuthenticatedUser(request), WeblogPermission.POST);
        PubStatus target = mayPublish ? PubStatus.PUBLISHED : PubStatus.PENDING;

        return applyToSelection(request, selectedEntries, redirectAttributes,
                mayPublish ? "weblogEntryQuery.bulkPublished" : "weblogEntryQuery.bulkSubmitted",
                entry -> {
                    if (target == PubStatus.PUBLISHED && !entry.isPublished()) {
                        // The tag cloud counts published entries only, so a
                        // draft becoming published must add all of its tags.
                        entry.setRefreshAggregates(true);
                    }
                    entry.setStatus(target);
                    entry.setUpdateTime(new Timestamp(System.currentTimeMillis()));
                    if (entry.isPublished() && entry.getPubTime() == null) {
                        entry.setPubTime(entry.getUpdateTime());
                    }
                    weblogger.getWeblogEntryManager().saveWeblogEntry(entry);

                    if (entry.isPublished()) {
                        weblogger.getIndexManager().addEntryReIndexOperation(entry);
                    }
                    CacheManager.invalidate(entry);
                });
    }

    /** Adds one tag to every selected entry, leaving the tags they already carry. */
    @PostMapping("/entries!bulkTag.rol")
    public String bulkTag(HttpServletRequest request,
                          @RequestParam(value = "selectedEntries", required = false)
                          List<String> selectedEntries,
                          @RequestParam(value = "bulkTag", required = false) String bulkTag,
                          RedirectAttributes redirectAttributes) {
        String tag = StringUtils.trimToNull(bulkTag);
        if (tag == null) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkTagMissing", request);
            return backToList(request);
        }

        return applyToSelection(request, selectedEntries, redirectAttributes,
                "weblogEntryQuery.bulkTagged",
                entry -> {
                    entry.addTag(tag);
                    weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
                    CacheManager.invalidate(entry);
                });
    }

    /**
     * Trashes every selected entry, each through the same path the single-entry
     * delete uses -- a bulk JPQL delete would leave the search index holding
     * documents for entries that no longer exist.
     */
    @PostMapping("/entries!bulkDelete.rol")
    public String bulkDelete(HttpServletRequest request,
                             @RequestParam(value = "selectedEntries", required = false)
                             List<String> selectedEntries,
                             RedirectAttributes redirectAttributes) {
        return applyToSelection(request, selectedEntries, redirectAttributes,
                "weblogEntryQuery.bulkDeleted", this::trashEntryWithIndex);
    }

    /** What one bulk action does to one entry, once it has been found and vetted. */
    @FunctionalInterface
    private interface EntryAction {
        void apply(WeblogEntry entry) throws WebloggerException;
    }

    /**
     * The shared body of every bulk action: resolve each selected id through
     * the ownership check, apply the action, and report honestly.
     *
     * <p>Two decisions worth stating. First, each id goes through
     * {@code lookupEntry} individually -- the ids are client input, and a
     * single foreign id slipped into the form would otherwise let an editor on
     * one weblog rewrite or delete another's posts. Second, an id that fails is
     * counted and reported rather than aborting the batch: rolling the whole
     * selection back because one entry was deleted in another tab would be a
     * worse answer than doing the other nineteen and saying so.
     */
    private String applyToSelection(HttpServletRequest request, List<String> selectedEntries,
                                    RedirectAttributes redirectAttributes, String successKey,
                                    EntryAction action) {
        if (selectedEntries == null || selectedEntries.isEmpty()) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkNothingSelected", request);
            return backToList(request);
        }

        int applied = 0;
        List<String> failed = new ArrayList<>();
        for (String id : selectedEntries) {
            // lookupNonTrashedEntry, not lookupEntry: a trashed id slipped
            // into a bulk selection must not be actionable -- bulkPublish in
            // particular would otherwise resurrect a trashed entry straight
            // to PUBLISHED, bypassing restore entirely.
            WeblogEntry entry = lookupNonTrashedEntry(id, request);
            if (entry == null) {
                failed.add(id);
                continue;
            }
            try {
                action.apply(entry);
                applied++;
            } catch (Exception e) {
                log.error("Bulk action failed for entry {}", id, e);
                failed.add(id);
            }
        }

        try {
            weblogger.flush();
        } catch (WebloggerException e) {
            log.error("Error flushing bulk action", e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
            return backToList(request);
        }

        if (applied > 0) {
            addFlashMessage(redirectAttributes, successKey, String.valueOf(applied), request);
        }
        if (!failed.isEmpty()) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkFailed",
                    String.valueOf(failed.size()), request);
        }
        return backToList(request);
    }

    private String backToList(HttpServletRequest request) {
        return "redirect:/roller-ui/authoring/entries.rol?weblog="
                + getActionWeblog(request).getHandle();
    }

    private String buildBaseUrl(HttpServletRequest request, EntriesBean bean) {
        Map<String, String> params = new HashMap<>();
        if (!StringUtils.isEmpty(bean.getCategoryName())) {
            params.put("bean.categoryPath", bean.getCategoryName());
        }
        if (!StringUtils.isEmpty(bean.getTagsAsString())) {
            params.put("bean.tagsAsString", bean.getTagsAsString());
        }
        if (!StringUtils.isEmpty(bean.getText())) {
            params.put("bean.text", bean.getText());
        }
        if (!StringUtils.isEmpty(bean.getStartDateString())) {
            params.put("bean.startDateString", bean.getStartDateString());
        }
        if (!StringUtils.isEmpty(bean.getEndDateString())) {
            params.put("bean.endDateString", bean.getEndDateString());
        }
        if (!StringUtils.isEmpty(bean.getStatus())) {
            params.put("bean.status", bean.getStatus());
        }
        if (bean.getSortBy() != null) {
            params.put("bean.sortBy", bean.getSortBy().toString());
        }
        return weblogger.getUrlStrategy().getActionURL("entries", "/roller-ui/authoring",
                getActionWeblog(request).getHandle(), params, false);
    }

    private List<WeblogCategory> getCategories(HttpServletRequest request) {
        List<WeblogCategory> cats = new ArrayList<>();
        WeblogCategory tmpCat = new WeblogCategory();
        tmpCat.setName("Any");
        cats.add(tmpCat);
        try {
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            cats.addAll(wmgr.getWeblogCategories(getActionWeblog(request)));
        } catch (WebloggerException ex) {
            log.error("Error getting category list", ex);
        }
        return cats;
    }

    private List<KeyValueObject> getSortByOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME.name(),
                getText("weblogEntryQuery.label.pubTime", request)));
        opts.add(new KeyValueObject(WeblogEntrySearchCriteria.SortBy.UPDATE_TIME.name(),
                getText("weblogEntryQuery.label.updateTime", request)));
        return opts;
    }

    private List<KeyValueObject> getStatusOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject("ALL", getText("weblogEntryQuery.label.allEntries", request)));
        opts.add(new KeyValueObject("DRAFT", getText("weblogEntryQuery.label.draftOnly", request)));
        opts.add(new KeyValueObject("PUBLISHED", getText("weblogEntryQuery.label.publishedOnly", request)));
        opts.add(new KeyValueObject("PENDING", getText("weblogEntryQuery.label.pendingOnly", request)));
        opts.add(new KeyValueObject("SCHEDULED", getText("weblogEntryQuery.label.scheduledOnly", request)));
        return opts;
    }

    @ModelAttribute("bean")
    public EntriesBean getBean() {
        return new EntriesBean();
    }
}
