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
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.ShareLink;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.apache.roller.weblogger.util.TextDiff;
import org.apache.roller.weblogger.util.TokenGenerator;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
        addEntryModelAttributes(request, model, entry, bean);
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
        addEntryModelAttributes(request, model, entry, bean);
        return result;
    }

    // --- entryEdit ---

    @GetMapping("/entryEdit.rol")
    public String entryEditExecute(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return ".EntryEdit";
    }

    /**
     * Renders unsaved editor text exactly as the published page will, and
     * returns the HTML fragment the editor shows in its preview pane.
     *
     * <p>The point is that it runs the <em>real</em> pipeline -- shortcode
     * expansion, then Markdown, then the sanitizer -- rather than a Markdown
     * library in the browser. Only the server can expand {@code [gallery]} or
     * {@code [map]}, and a preview that disagreed with the published page
     * about those would mislead precisely where an author most needs to trust
     * it.
     *
     * <p>The text comes from the request; the <em>entry</em> comes from
     * {@code lookupEntry}, so the weblog-ownership check applies here as it
     * does to every other entry action. A brand-new entry has no id yet and
     * previews against a scratch entry owned by the action weblog, so its
     * shortcodes resolve that weblog's media and nothing is persisted.
     */
    @PostMapping("/entryEdit!preview.rol")
    @ResponseBody
    public ResponseEntity<String> entryEditPreview(HttpServletRequest request,
                                                   @RequestParam(value = "id", required = false) String id,
                                                   @RequestParam(value = "text", required = false) String text) {
        WeblogEntry entry;
        if (id != null && !id.isBlank()) {
            entry = lookupEntry(id, request);
            if (entry == null) {
                return ResponseEntity.notFound().build();
            }
        } else {
            Weblog weblog = getActionWeblog(request);
            if (weblog == null) {
                return ResponseEntity.notFound().build();
            }
            entry = new WeblogEntry();
            entry.setWebsite(weblog);
            entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        }

        entry.setText(text == null ? "" : text);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(entry.getTransformedText());
    }

    /**
     * The diff between one revision and the entry as it stands now.
     *
     * <p>Returns a fragment for the editor's revision modal rather than a
     * page. Both ids are client input, so both are checked: the entry through
     * the usual ownership lookup, and the revision by confirming it actually
     * belongs to that entry -- otherwise a revision id from another weblog
     * would render that weblog's unpublished text here.
     */
    @PostMapping("/entryEdit!revisionDiff.rol")
    @ResponseBody
    public ResponseEntity<String> entryEditRevisionDiff(HttpServletRequest request,
                                                        @RequestParam(value = "id", required = false) String id,
                                                        @RequestParam(value = "revisionId",
                                                                required = false)
                                                        String revisionId) {
        WeblogEntry entry = lookupEntry(id, request);
        WeblogEntryRevision revision = lookupRevision(revisionId, entry);
        if (revision == null) {
            return ResponseEntity.notFound().build();
        }

        StringBuilder html = new StringBuilder("<div class=\"revision-diff\">");
        appendDiff(html, getText("weblogEdit.title", request),
                revision.getTitle(), entry.getTitle());
        appendDiff(html, getText("weblogEdit.summary", request),
                revision.getSummary(), entry.getSummary());
        appendDiff(html, getText("weblogEdit.content", request),
                revision.getText(), entry.getText());
        html.append("</div>");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html.toString());
    }

    /**
     * Appends one labelled section of the diff, or nothing at all when that
     * field did not change -- three "no changes" headings would bury the one
     * section the author opened the modal to read.
     */
    private void appendDiff(StringBuilder html, String label, String before, String after) {
        List<TextDiff.Line> lines = TextDiff.diff(before, after);
        if (lines.isEmpty()) {
            return;
        }
        html.append("<h5>").append(HTMLSanitizer.htmlEncodeTag(label)).append("</h5><pre>");
        for (TextDiff.Line line : lines) {
            String cssClass = switch (line.kind()) {
                case ADDED -> "diff-added";
                case REMOVED -> "diff-removed";
                case SAME -> "diff-same";
            };
            String marker = switch (line.kind()) {
                case ADDED -> "+ ";
                case REMOVED -> "- ";
                case SAME -> "  ";
            };
            // The revision holds the author's raw Markdown, which may contain
            // any amount of HTML. It is being shown as source, so every
            // character of it is escaped rather than rendered.
            html.append("<span class=\"").append(cssClass).append("\">").append(marker)
                    .append(HTMLSanitizer.htmlEncodeTag(line.text())).append("</span>\n");
        }
        html.append("</pre>");
    }

    /**
     * Puts a revision's content back, through the ordinary save path so the
     * state it replaces becomes a revision of its own. Restoring is therefore
     * undoable by restoring again, and never loses work.
     */
    @PostMapping("/entryEdit!restoreRevision.rol")
    public String entryEditRestoreRevision(HttpServletRequest request,
                                           @RequestParam(value = "bean.id", required = false)
                                           String id,
                                           @RequestParam(value = "revisionId", required = false) String revisionId,
                                           RedirectAttributes redirectAttributes) {
        WeblogEntry entry = lookupEntry(id, request);
        WeblogEntryRevision revision = lookupRevision(revisionId, entry);
        if (revision == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return "redirect:/roller-ui/authoring/entries.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }

        try {
            entry.setTitle(revision.getTitle());
            entry.setText(revision.getText());
            entry.setSummary(revision.getSummary());
            weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
            weblogger.flush();

            if (entry.isPublished()) {
                weblogger.getIndexManager().addEntryReIndexOperation(entry);
            }
            CacheManager.invalidate(entry);

            addFlashMessage(redirectAttributes, "weblogEdit.revisionRestored", request);
        } catch (Exception e) {
            log.error("Error restoring revision " + revisionId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return redirectToEntryEdit(request, entry);
    }

    /**
     * The revision with this id, but only when it belongs to {@code entry}
     * (which the caller has already established they may edit). Null for an
     * unknown id, a foreign one, or a null entry -- indistinguishable to the
     * caller, exactly like {@code lookupEntry}.
     */
    private WeblogEntryRevision lookupRevision(String revisionId, WeblogEntry entry) {
        if (entry == null || revisionId == null) {
            return null;
        }
        try {
            WeblogEntryRevision revision =
                    weblogger.getWeblogEntryManager().getRevision(revisionId);
            if (revision == null || revision.getWeblogEntry() == null
                    || !entry.getId().equals(revision.getWeblogEntry().getId())) {
                return null;
            }
            return revision;
        } catch (WebloggerException e) {
            log.error("Error looking up revision " + revisionId, e);
            return null;
        }
    }

    @GetMapping("/entryEdit!firstSave.rol")
    public String entryEditFirstSave(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        addStatusMessage(entry.getStatus(), model, entry, request);
        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
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

    /**
     * Creates (or replaces) the entry's share link, hashing the optional
     * password here -- the manager tier stores hashes as given and never sees
     * plaintext. Redirects back to the editor, which shows the new URL in the
     * share card.
     */
    @PostMapping("/entryEdit!createShareLink.rol")
    public String entryEditCreateShareLink(HttpServletRequest request,
                                           RedirectAttributes redirectAttributes,
                                           @RequestParam(value = "entryId", required = false) String entryId,
                                           @RequestParam(value = "sharePassword", required = false) String sharePassword) {
        WeblogEntry entry = lookupEntry(entryId, request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }
        try {
            ShareLink existing = weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_ENTRY, entry.getId());
            if (existing != null) {
                weblogger.getShareLinkManager().removeShareLink(existing);
            }
            ShareLink link = new ShareLink();
            link.setWeblog(getActionWeblog(request));
            link.setTargetType(ShareLink.TYPE_ENTRY);
            link.setTargetId(entry.getId());
            link.setToken(TokenGenerator.newToken());
            if (StringUtils.isNotBlank(sharePassword)) {
                link.setPasswordHash(RollerContext.getPasswordEncoder().encode(sharePassword));
            }
            weblogger.getShareLinkManager().createShareLink(link);
            weblogger.flush();
            addFlashMessage(redirectAttributes, "shareLink.created", request);
        } catch (WebloggerException e) {
            log.error("Error creating share link for entry " + entryId, e);
            addFlashError(redirectAttributes, "shareLink.error", request);
        }
        return redirectToEntryEdit(request, entry);
    }

    /** Revokes the entry's share link; the entry itself is untouched. */
    @PostMapping("/entryEdit!revokeShareLink.rol")
    public String entryEditRevokeShareLink(HttpServletRequest request,
                                           RedirectAttributes redirectAttributes,
                                           @RequestParam(value = "entryId", required = false) String entryId) {
        WeblogEntry entry = lookupEntry(entryId, request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }
        try {
            ShareLink link = weblogger.getShareLinkManager()
                    .getShareLinkForTarget(ShareLink.TYPE_ENTRY, entry.getId());
            if (link == null) {
                addFlashError(redirectAttributes, "shareLink.error", request);
            } else {
                weblogger.getShareLinkManager().removeShareLink(link);
                weblogger.flush();
                addFlashMessage(redirectAttributes, "shareLink.revoked", request);
            }
        } catch (WebloggerException e) {
            log.error("Error revoking share link for entry " + entryId, e);
            addFlashError(redirectAttributes, "shareLink.error", request);
        }
        return redirectToEntryEdit(request, entry);
    }

    private String redirectToEntryEdit(HttpServletRequest request, WeblogEntry entry) {
        return "redirect:/roller-ui/authoring/entryEdit.rol?weblog="
                + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
    }

    private String doEntryEditSave(HttpServletRequest request, Model model, EntryBean bean, String action) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId(), request);
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
        addEntryModelAttributes(request, model, entry, bean);
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
                WeblogEntryManager weblogEntryManager = weblogger.getWeblogEntryManager();
                IndexManager indexMgr = weblogger.getIndexManager();

                entry.setUpdateTime(new Timestamp(new Date().getTime()));
                entry.setPubTime(bean.getPubTime(request.getLocale(),
                        getActionWeblog(request).getTimeZoneInstance()));

                bean.copyTo(entry);

                if (entry.isPublished() && entry.getPubTime() == null) {
                    entry.setPubTime(entry.getUpdateTime());
                }

                GlobalPermission adminPerm = new GlobalPermission(
                        Collections.singletonList(GlobalPermission.ADMIN));
                if (weblogger.getUserManager()
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
                weblogger.flush();

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

    private void addEntryModelAttributes(HttpServletRequest request, Model model, WeblogEntry entry,
                                         EntryBean bean) {
        model.addAttribute("categories", getCategories(request));
        model.addAttribute("entryPlugins", getEntryPlugins(request));
        model.addAttribute("userAnAuthor", getActionWeblog(request).hasUserPermission(
                getAuthenticatedUser(request), WeblogPermission.POST));
        model.addAttribute("jsonAutocompleteUrl", weblogger.getUrlStrategy()
                .getWeblogTagsJsonURL(getActionWeblog(request), false, 0));

        // The editor's insert menu, generated from the shortcode registry
        // itself so it can never advertise a shortcode that does not render,
        // or omit one that does.
        model.addAttribute("shortcodeCards", ShortcodeExpander.defaultExpander().cards());

        if (entry.getId() != null) {
            try {
                model.addAttribute("entryRevisions",
                        weblogger.getWeblogEntryManager().getRevisions(entry));
            } catch (WebloggerException e) {
                log.error("Error loading revisions for entry " + entry.getId(), e);
            }
        }

        if (entry.getId() != null) {
            model.addAttribute("previewURL", weblogger.getUrlStrategy()
                    .getPreviewURLStrategy(null)
                    .getWeblogEntryURL(getActionWeblog(request), null, entry.getAnchor(), true));

            // share-link card state for this entry (sidebar on EntryEdit.jsp)
            try {
                ShareLink shareLink = weblogger.getShareLinkManager()
                        .getShareLinkForTarget(ShareLink.TYPE_ENTRY, entry.getId());
                if (shareLink != null) {
                    model.addAttribute("entryShareLink", shareLink);
                    model.addAttribute("entryShareURL",
                            WebloggerRuntimeConfig.getAbsoluteContextURL()
                                    + "/share/" + shareLink.getToken());
                }
            } catch (WebloggerException e) {
                log.error("Error loading share link for entry " + entry.getId(), e);
            }
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

        // Thumbnail previews for the SEO panel's featured/social image pickers.
        // Read off the bean rather than the entry so a save that failed
        // validation still shows the image the author picked in the form.
        addImagePreviewAttribute(request, model, "featuredImageThumbnailUrl", bean.getFeaturedImageId());
        addImagePreviewAttribute(request, model, "ogImageThumbnailUrl", bean.getOgImageId());
    }

    /**
     * Adds the thumbnail URL for a referenced media file, if it still exists
     * and belongs to this weblog. A dangling id (image deleted after being
     * picked) simply renders no preview rather than a broken editor page, and
     * an id belonging to another weblog is treated exactly the same way --
     * {@code getMediaFile} is a global by-id lookup and these ids come off the
     * submitted form.
     */
    private void addImagePreviewAttribute(HttpServletRequest request, Model model,
                                          String attributeName, String mediaFileId) {
        if (StringUtils.isEmpty(mediaFileId)) {
            return;
        }
        try {
            MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(mediaFileId);
            if (mediaFile != null && getActionWeblog(request).equals(mediaFile.getWeblog())) {
                model.addAttribute(attributeName, weblogger.getUrlStrategy()
                        .getMediaFileThumbnailURL(mediaFile.getWeblog(), mediaFile.getId(), true));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up media file - " + mediaFileId, ex);
        }
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
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            return wmgr.getWeblogCategories(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error getting category list", ex);
            return Collections.emptyList();
        }
    }

    private List<WeblogEntryPlugin> getEntryPlugins(HttpServletRequest request) {
        try {
            PluginManager ppmgr = weblogger.getPluginManager();
            Map<String, WeblogEntryPlugin> plugins = ppmgr.getWeblogEntryPlugins(getActionWeblog(request));
            if (!plugins.isEmpty()) {
                return new ArrayList<>(plugins.values());
            }
        } catch (Exception ex) {
            log.error("Error getting plugins list", ex);
        }
        return Collections.emptyList();
    }

    private List<WeblogEntry> getRecentEntries(HttpServletRequest request, PubStatus pubStatus,
                                               WeblogEntrySearchCriteria.SortBy sortBy) {
        try {
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(getActionWeblog(request));
            wesc.setMaxResults(20);
            wesc.setStatus(pubStatus);
            wesc.setSortBy(sortBy);
            return weblogger.getWeblogEntryManager().getWeblogEntries(wesc);
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
