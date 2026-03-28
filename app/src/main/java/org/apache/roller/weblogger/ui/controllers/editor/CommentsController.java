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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.CommentSearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogEntryComment.ApprovalStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.pagers.CommentsPager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.I18nMessages;
import org.apache.roller.weblogger.util.MailUtil;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Action for managing weblog comments.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class CommentsController extends BaseController {

    private static final Log log = LogFactory.getLog(CommentsController.class);
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
        return "comments";
    }

    @Override
    public String getPageTitle() {
        return "commentManagement.title";
    }

    @GetMapping("/comments.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") CommentsBean bean) {
        populateCommonModel(request, model);
        loadComments(request, model, bean);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());
        return ".Comments";
    }

    @PostMapping("/comments!query.rol")
    public String query(HttpServletRequest request, Model model,
                        @ModelAttribute("bean") CommentsBean bean) {
        populateCommonModel(request, model);
        loadComments(request, model, bean);
        bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            CommentSearchCriteria csc = getCommentSearchCriteria(request, bean);
            List<WeblogEntryComment> allMatchingComments = wmgr.getComments(csc);
            if (allMatchingComments.size() > COUNT) {
                model.addAttribute("bulkDeleteCount", allMatchingComments.size());
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up comments", ex);
            addError(model, "Error looking up comments", request);
        }

        return ".Comments";
    }

    @PostMapping("/comments!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") CommentsBean bean) {
        populateCommonModel(request, model);

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            Set<WeblogEntry> reindexEntries = new HashSet<>();

            if (WebloggerConfig.getBooleanProperty("search.enabled")) {
                CommentSearchCriteria csc = getCommentSearchCriteria(request, bean);
                List<WeblogEntryComment> targetted = wmgr.getComments(csc);
                for (WeblogEntryComment comment : targetted) {
                    reindexEntries.add(comment.getWeblogEntry());
                }
            }

            int deleted = wmgr.removeMatchingComments(getActionWeblog(request), null,
                    bean.getSearchString(), bean.getStartDate(), bean.getEndDate(), bean.getStatus());

            if (!reindexEntries.isEmpty()) {
                IndexManager imgr = WebloggerFactory.getWeblogger().getIndexManager();
                for (WeblogEntry entry : reindexEntries) {
                    imgr.addEntryReIndexOperation(entry);
                }
            }

            addMessage(model, "commentManagement.deleteSuccess", Integer.toString(deleted), request);

            // reset bean
            bean = new CommentsBean();
            model.addAttribute("bean", bean);
            loadComments(request, model, bean);
            bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        } catch (WebloggerException ex) {
            log.error("Error doing bulk delete", ex);
            addError(model, "Bulk delete failed due to unexpected error", request);
        }

        return ".Comments";
    }

    @PostMapping("/comments!update.rol")
    public String update(HttpServletRequest request, Model model,
                         @ModelAttribute("bean") CommentsBean bean) {
        populateCommonModel(request, model);

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            List<WeblogEntryComment> flushList = new ArrayList<>();
            Set<WeblogEntry> reindexList = new HashSet<>();

            List<String> deletes = Arrays.asList(bean.getDeleteComments());
            if (!deletes.isEmpty()) {
                for (String deleteId : deletes) {
                    WeblogEntryComment deleteComment = wmgr.getComment(deleteId);
                    if (getActionWeblog(request).equals(deleteComment.getWeblogEntry().getWebsite())) {
                        flushList.add(deleteComment);
                        reindexList.add(deleteComment.getWeblogEntry());
                        wmgr.removeComment(deleteComment);
                    }
                }
            }

            List<String> approvedIds = Arrays.asList(bean.getApprovedComments());
            List<String> spamIds = Arrays.asList(bean.getSpamComments());
            List<WeblogEntryComment> approvedComments = new ArrayList<>();

            String[] ids = Utilities.stringToStringArray(bean.getIds(), ",");
            for (String id : ids) {
                if (deletes.contains(id)) {
                    continue;
                }

                WeblogEntryComment comment = wmgr.getComment(id);
                if (getActionWeblog(request).equals(comment.getWeblogEntry().getWebsite())) {
                    if (approvedIds.contains(id)) {
                        if (ApprovalStatus.PENDING.equals(comment.getStatus())) {
                            approvedComments.add(comment);
                        }
                        comment.setStatus(ApprovalStatus.APPROVED);
                        wmgr.saveComment(comment);
                        flushList.add(comment);
                        reindexList.add(comment.getWeblogEntry());
                    } else if (spamIds.contains(id)) {
                        comment.setStatus(ApprovalStatus.SPAM);
                        wmgr.saveComment(comment);
                        flushList.add(comment);
                        reindexList.add(comment.getWeblogEntry());
                    } else if (!ApprovalStatus.DISAPPROVED.equals(comment.getStatus())) {
                        comment.setStatus(ApprovalStatus.DISAPPROVED);
                        wmgr.saveComment(comment);
                        flushList.add(comment);
                        reindexList.add(comment.getWeblogEntry());
                    }
                }
            }

            WebloggerFactory.getWeblogger().flush();
            CacheManager.invalidate(getActionWeblog(request));

            if (MailUtil.isMailConfigured()) {
                I18nMessages resources = I18nMessages.getMessages(getActionWeblog(request).getLocaleInstance());
                MailUtil.sendEmailApprovalNotifications(approvedComments, resources);
            }

            if (!reindexList.isEmpty()) {
                IndexManager imgr = WebloggerFactory.getWeblogger().getIndexManager();
                for (WeblogEntry entry : reindexList) {
                    imgr.addEntryReIndexOperation(entry);
                }
            }

            addMessage(model, "commentManagement.updateSuccess", request);

            // reset bean, maintain filters
            CommentsBean freshBean = new CommentsBean();
            freshBean.setSearchString(bean.getSearchString());
            freshBean.setStartDateString(bean.getStartDateString());
            freshBean.setEndDateString(bean.getEndDateString());
            freshBean.setApprovedString(bean.getApprovedString());
            if (bean.getEntryId() != null) {
                freshBean.setEntryId(bean.getEntryId());
            }
            bean = freshBean;
            model.addAttribute("bean", bean);

            loadComments(request, model, bean);
            bean.loadCheckboxes(((CommentsPager) model.getAttribute("pager")).getItems());

        } catch (Exception ex) {
            log.error("ERROR updating comments", ex);
            addError(model, "commentManagement.updateError", ex.toString(), request);
        }

        return ".Comments";
    }

    private void loadComments(HttpServletRequest request, Model model, CommentsBean bean) {
        List<WeblogEntryComment> comments = Collections.emptyList();
        boolean hasMore = false;
        WeblogEntry queryEntry = null;

        try {
            WeblogEntryManager wmgr = WebloggerFactory.getWeblogger().getWeblogEntryManager();
            if (!StringUtils.isEmpty(bean.getEntryId())) {
                queryEntry = wmgr.getWeblogEntry(bean.getEntryId());
            }

            CommentSearchCriteria csc = getCommentSearchCriteria(request, bean);
            if (queryEntry != null) {
                csc.setEntry(queryEntry);
            }
            csc.setOffset(bean.getPage() * COUNT);
            csc.setMaxResults(COUNT + 1);

            List<WeblogEntryComment> rawComments = wmgr.getComments(csc);
            comments = new ArrayList<>(rawComments);
            if (!comments.isEmpty()) {
                if (comments.size() > COUNT) {
                    comments.remove(comments.size() - 1);
                    hasMore = true;
                }
                model.addAttribute("firstComment", comments.get(0));
                model.addAttribute("lastComment", comments.get(comments.size() - 1));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up comments", ex);
        }

        model.addAttribute("queryEntry", queryEntry);
        model.addAttribute("pager", new CommentsPager(buildBaseUrl(request, bean), bean.getPage(), comments, hasMore));
        model.addAttribute("commentStatusOptions", getCommentStatusOptions(request));
    }

    private CommentSearchCriteria getCommentSearchCriteria(HttpServletRequest request, CommentsBean bean) {
        CommentSearchCriteria csc = new CommentSearchCriteria();
        csc.setWeblog(getActionWeblog(request));
        csc.setSearchText(bean.getSearchString());
        csc.setStartDate(bean.getStartDate());
        csc.setEndDate(bean.getEndDate());
        csc.setStatus(bean.getStatus());
        csc.setReverseChrono(true);
        return csc;
    }

    private String buildBaseUrl(HttpServletRequest request, CommentsBean bean) {
        Map<String, String> params = new HashMap<>();
        if (!StringUtils.isEmpty(bean.getEntryId())) {
            params.put("bean.entryId", bean.getEntryId());
        }
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
        return WebloggerFactory.getWeblogger().getUrlStrategy()
                .getActionURL("comments", "/roller-ui/authoring", getActionWeblog(request).getHandle(), params, false);
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

    @ModelAttribute("bean")
    public CommentsBean getBean() {
        return new CommentsBean();
    }
}
