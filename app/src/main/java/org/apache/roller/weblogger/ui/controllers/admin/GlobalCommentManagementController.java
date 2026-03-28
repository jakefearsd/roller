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

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.pagers.CommentsPager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.apache.roller.weblogger.util.Utilities;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Action for managing global set of comments.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class GlobalCommentManagementController extends BaseController {

    private static final Log log = LogFactory.getLog(GlobalCommentManagementController.class);

    // number of comments to show per page
    private static final int COUNT = 30;

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "commentManagement.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "globalCommentManagement";
    }

    @ModelAttribute("bean")
    public GlobalCommentManagementBean getBean() {
        return new GlobalCommentManagementBean();
    }

    /**
     * Show comment management page.
     */
    @GetMapping("/globalCommentManagement.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        GlobalCommentManagementBean bean = new GlobalCommentManagementBean();
        model.addAttribute("bean", bean);

        loadComments(bean, model, request);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        model.addAttribute("commentStatusOptions", getCommentStatusOptions(request));
        model.addAttribute("bulkDeleteCount", 0);

        return ".GlobalCommentManagement";
    }

    /**
     * Query for a specific subset of comments based on various criteria.
     */
    @GetMapping("/globalCommentManagement!query.rol")
    public String query(HttpServletRequest request, Model model,
                        @ModelAttribute("bean") GlobalCommentManagementBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("bean", bean);

        loadComments(bean, model, request);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        int bulkDeleteCount = 0;
        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();

            CommentSearchCriteria csc = new CommentSearchCriteria();
            csc.setSearchText(bean.getSearchString());
            csc.setStartDate(bean.getStartDate());
            csc.setEndDate(bean.getEndDate());
            csc.setStatus(bean.getStatus());
            csc.setReverseChrono(true);

            List<WeblogEntryComment> allMatchingComments = wmgr.getComments(csc);
            if (allMatchingComments.size() > COUNT) {
                bulkDeleteCount = allMatchingComments.size();
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up comments", ex);
            addError(model, "commentManagement.lookupError", request);
        }

        model.addAttribute("bulkDeleteCount", bulkDeleteCount);
        model.addAttribute("commentStatusOptions", getCommentStatusOptions(request));

        return ".GlobalCommentManagement";
    }

    /**
     * Bulk delete all comments matching query criteria.
     */
    @PostMapping("/globalCommentManagement!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") GlobalCommentManagementBean bean) {
        populateCommonModel(request, model);

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            int deleted = wmgr.removeMatchingComments(
                    null,
                    null,
                    bean.getSearchString(),
                    bean.getStartDate(),
                    bean.getEndDate(),
                    bean.getStatus());

            addMessage(model, "commentManagement.deleteSuccess", Integer.toString(deleted), request);

        } catch (WebloggerException ex) {
            log.error("Error doing bulk delete", ex);
            addError(model, "commentManagement.deleteError", request);
        }

        // reset form and load fresh comments list
        bean = new GlobalCommentManagementBean();
        model.addAttribute("bean", bean);

        loadComments(bean, model, request);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        model.addAttribute("commentStatusOptions", getCommentStatusOptions(request));
        model.addAttribute("bulkDeleteCount", 0);

        return ".GlobalCommentManagement";
    }

    /**
     * Update a list of comments.
     */
    @PostMapping("/globalCommentManagement!update.rol")
    public String update(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") GlobalCommentManagementBean bean) {
        populateCommonModel(request, model);

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();

            List<Weblog> flushList = new ArrayList<>();

            // delete all comments with delete box checked
            List<String> deletes = Arrays.asList(bean.getDeleteComments());
            if (!deletes.isEmpty()) {
                log.debug("Processing deletes - " + deletes.size());

                WeblogEntryComment deleteComment;
                for (String deleteId : deletes) {
                    deleteComment = wmgr.getComment(deleteId);
                    flushList.add(deleteComment.getWeblogEntry().getWebsite());
                    wmgr.removeComment(deleteComment);
                }
            }

            // loop through IDs of all comments displayed on page
            List<String> spamIds = Arrays.asList(bean.getSpamComments());
            log.debug(spamIds.size() + " comments marked as spam");

            String[] ids = Utilities.stringToStringArray(bean.getIds(), ",");
            for (String id : ids) {
                log.debug("processing id - " + id);

                // if we already deleted it then skip forward
                if (deletes.contains(id)) {
                    log.debug("Already deleted, skipping - " + id);
                    continue;
                }

                WeblogEntryComment comment = wmgr.getComment(id);

                // mark/unmark spam
                if (spamIds.contains(id) &&
                        !ApprovalStatus.SPAM.equals(comment.getStatus())) {
                    log.debug("Marking as spam - " + comment.getId());
                    comment.setStatus(ApprovalStatus.SPAM);
                    wmgr.saveComment(comment);
                    flushList.add(comment.getWeblogEntry().getWebsite());
                } else if (!spamIds.contains(id) &&
                        ApprovalStatus.SPAM.equals(comment.getStatus())) {
                    log.debug("Marking as disapproved - " + comment.getId());
                    comment.setStatus(ApprovalStatus.DISAPPROVED);
                    wmgr.saveComment(comment);
                    flushList.add(comment.getWeblogEntry().getWebsite());
                }
            }

            WebloggerFactory.getWeblogger().flush();

            // notify caches of changes, flush weblogs affected by changes
            for (Weblog weblog : flushList) {
                CacheManager.invalidate(weblog);
            }

            addMessage(model, "commentManagement.updateSuccess", request);

        } catch (Exception ex) {
            log.error("ERROR updating comments", ex);
            addError(model, "commentManagement.updateError", request);
        }

        // reset form and load fresh comments list
        bean = new GlobalCommentManagementBean();
        model.addAttribute("bean", bean);

        loadComments(bean, model, request);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        model.addAttribute("commentStatusOptions", getCommentStatusOptions(request));
        model.addAttribute("bulkDeleteCount", 0);

        return ".GlobalCommentManagement";
    }

    private void loadComments(GlobalCommentManagementBean bean, Model model,
                              HttpServletRequest request) {

        List<WeblogEntryComment> comments = Collections.emptyList();
        boolean hasMore = false;
        WeblogEntryComment firstComment = null;
        WeblogEntryComment lastComment = null;

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();

            CommentSearchCriteria csc = new CommentSearchCriteria();
            csc.setSearchText(bean.getSearchString());
            csc.setStartDate(bean.getStartDate());
            csc.setEndDate(bean.getEndDate());
            csc.setStatus(bean.getStatus());
            csc.setReverseChrono(true);
            csc.setOffset(bean.getPage() * COUNT);
            csc.setMaxResults(COUNT + 1);

            List<WeblogEntryComment> rawComments = wmgr.getComments(csc);
            comments = new ArrayList<>(rawComments);

            if (!comments.isEmpty()) {
                if (comments.size() > COUNT) {
                    comments.remove(comments.size() - 1);
                    hasMore = true;
                }
                firstComment = comments.get(0);
                lastComment = comments.get(comments.size() - 1);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up comments", ex);
            addError(model, "commentManagement.lookupError", request);
        }

        // build comments pager
        String baseUrl = buildBaseUrl(bean);
        CommentsPager pager = new CommentsPager(baseUrl, bean.getPage(), comments, hasMore);

        model.addAttribute("pager", pager);
        model.addAttribute("firstComment", firstComment);
        model.addAttribute("lastComment", lastComment);
    }

    private String buildBaseUrl(GlobalCommentManagementBean bean) {
        Map<String, String> params = new HashMap<>();

        if (!StringUtils.isEmpty(bean.getSearchString())) {
            params.put("bean.searchString", bean.getSearchString());
        }
        if (!StringUtils.isEmpty(bean.getStartDateString())) {
            params.put("bean.startDateString", bean.getStartDateString());
        }
        if (!StringUtils.isEmpty(bean.getEndDateString())) {
            params.put("bean.endDateString", bean.getEndDateString());
        }
        if (!StringUtils.isEmpty(bean.getApprovedString())) {
            params.put("bean.approvedString", bean.getApprovedString());
        }

        return WebloggerFactory.getWeblogger().getUrlStrategy().getActionURL(
                "globalCommentManagement", "/roller-ui/admin", null, params, false);
    }

    private List<KeyValueObject> getCommentStatusOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject("ALL", getText("generic.all", request)));
        opts.add(new KeyValueObject("ONLY_PENDING", getText("commentManagement.onlyPending", request)));
        opts.add(new KeyValueObject("ONLY_APPROVED", getText("commentManagement.onlyApproved", request)));
        opts.add(new KeyValueObject("ONLY_DISAPPROVED", getText("commentManagement.onlyDisapproved", request)));
        opts.add(new KeyValueObject("ONLY_SPAM", getText("commentManagement.onlySpam", request)));
        return opts;
    }
}
