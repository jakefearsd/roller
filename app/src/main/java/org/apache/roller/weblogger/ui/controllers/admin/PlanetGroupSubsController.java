/*
 * Copyright 2005 Sun Microsystems, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.RollerException;
import org.apache.roller.planet.business.PlanetManager;
import org.apache.roller.planet.business.fetcher.FeedFetcher;
import org.apache.roller.planet.business.fetcher.FetcherException;
import org.apache.roller.planet.pojos.Planet;
import org.apache.roller.planet.pojos.PlanetGroup;
import org.apache.roller.planet.pojos.Subscription;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Manage planet group subscriptions, default group is "all".
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class PlanetGroupSubsController extends BaseController {

    private static final Log log = LogFactory.getLog(PlanetGroupSubsController.class);

    public static final String DEFAULT_PLANET_HANDLE = "default";

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "planetGroupSubs";
    }

    /**
     * Populate page model and forward to subscription page.
     */
    @GetMapping("/planetGroupSubs.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Planet planet = getPlanet();
        boolean createNew = request.getParameter("createNew") != null;
        PlanetGroup group;

        if (createNew) {
            group = new PlanetGroup();
        } else {
            group = getGroupFromRequest(request, planet);
        }

        populateGroupModel(group, createNew, model, request);

        return ".PlanetGroupSubs";
    }

    /**
     * Save group.
     */
    @PostMapping("/planetGroupSubs!saveGroup.rol")
    public String saveGroup(HttpServletRequest request, Model model,
                            @RequestParam(value = "group.id", required = false) String groupId,
                            @RequestParam(value = "group.title", required = false) String groupTitle,
                            @RequestParam(value = "group.handle", required = false) String groupHandle) {
        populateCommonModel(request, model);

        Planet planet = getPlanet();
        PlanetGroup group;
        boolean createNew;

        if (!StringUtils.isEmpty(groupId)) {
            group = getGroupById(groupId);
            createNew = (group == null);
            if (createNew) {
                group = new PlanetGroup();
            }
        } else {
            group = new PlanetGroup();
            createNew = true;
        }

        group.setTitle(groupTitle);
        group.setHandle(groupHandle);

        // validate
        if (StringUtils.isEmpty(groupTitle)) {
            addError(model, "planetGroups.error.title", request);
        }
        if (StringUtils.isEmpty(groupHandle)) {
            addError(model, "planetGroups.error.handle", request);
        }
        if ("all".equals(groupHandle)) {
            addError(model, "planetGroups.error.nameReserved", request);
        }

        if (!hasErrors(model)) {
            try {
                PlanetManager planetManager = WebloggerFactory.getWeblogger().getPlanetManager();
                PlanetGroup existingGroup = planetManager.getGroup(planet, groupHandle);

                if (existingGroup == null) {
                    log.debug("Adding New Group: " + groupHandle);
                    planetManager.saveNewPlanetGroup(planet, group);
                } else {
                    log.debug("Updating Existing Group: " + existingGroup.getHandle());
                    existingGroup.setTitle(groupTitle);
                    existingGroup.setHandle(groupHandle);
                    planetManager.saveGroup(existingGroup);
                    group = existingGroup;
                }

                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "planetGroups.success.saved", request);

            } catch (Exception ex) {
                log.error("Error saving planet group", ex);
                addError(model, "planetGroups.error.saved", request);
            }
        }

        populateGroupModel(group, false, model, request);
        return ".PlanetGroupSubs";
    }

    /**
     * Save subscription, add to current group.
     */
    @PostMapping("/planetGroupSubs!saveSubscription.rol")
    public String saveSubscription(HttpServletRequest request, Model model,
                                   @RequestParam(value = "group.handle", required = false) String groupHandle,
                                   @RequestParam(value = "subUrl", required = false) String subUrl) {
        populateCommonModel(request, model);

        Planet planet = getPlanet();
        PlanetGroup group = getGroupByHandle(planet, groupHandle);

        if (StringUtils.isEmpty(subUrl)) {
            addError(model, "planetSubscription.error.feedUrl", request);
        }

        if (!hasErrors(model) && group != null) {
            try {
                PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();

                // check if this subscription already exists before adding it
                Subscription sub = pmgr.getSubscription(subUrl);
                if (sub == null) {
                    log.debug("Adding New Subscription - " + subUrl);
                    FeedFetcher fetcher = WebloggerFactory.getWeblogger().getFeedFetcher();
                    sub = fetcher.fetchSubscription(subUrl);
                    pmgr.saveSubscription(sub);
                } else {
                    log.debug("Adding Existing Subscription - " + subUrl);
                }

                // add the sub to the group
                group.getSubscriptions().add(sub);
                sub.getGroups().add(group);
                pmgr.saveGroup(group);
                WebloggerFactory.getWeblogger().flush();

                subUrl = null;
                addMessage(model, "planetSubscription.success.saved", request);

            } catch (FetcherException ex) {
                addError(model, "planetGroupSubs.error.fetchingFeed", request);
            } catch (RollerException ex) {
                log.error("Unexpected error saving subscription", ex);
                addError(model, "planetGroupSubs.error.duringSave", request);
            }
        }

        model.addAttribute("subUrl", subUrl);
        populateGroupModel(group, false, model, request);
        return ".PlanetGroupSubs";
    }

    /**
     * Delete subscription, reset form.
     */
    @PostMapping("/planetGroupSubs!deleteSubscription.rol")
    public String deleteSubscription(HttpServletRequest request, Model model,
                                     @RequestParam(value = "group.handle", required = false) String groupHandle,
                                     @RequestParam(value = "subUrl", required = false) String subUrl) {
        populateCommonModel(request, model);

        Planet planet = getPlanet();
        PlanetGroup group = getGroupByHandle(planet, groupHandle);

        if (subUrl != null && group != null) {
            try {
                PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
                Subscription sub = pmgr.getSubscription(subUrl);

                // remove sub from group
                group.getSubscriptions().remove(sub);
                pmgr.saveGroup(group);

                // remove group from sub
                sub.getGroups().remove(group);
                pmgr.saveSubscription(sub);

                WebloggerFactory.getWeblogger().flush();

                addMessage(model, "planetSubscription.success.deleted", request);

            } catch (RollerException ex) {
                log.error("Error removing planet subscription", ex);
                addError(model, "planetSubscription.error.deleting", request);
            }
        }

        populateGroupModel(group, false, model, request);
        return ".PlanetGroupSubs";
    }

    // --- Helpers ---

    private void populateGroupModel(PlanetGroup group, boolean createNew, Model model,
                                    HttpServletRequest request) {
        model.addAttribute("group", group);
        model.addAttribute("groupHandle", group != null ? group.getHandle() : "all");

        if (!createNew && group != null) {
            // check if it's truly new by looking it up
            PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
            try {
                PlanetGroup existing = (group.getId() != null) ? pmgr.getGroupById(group.getId()) : null;
                createNew = (existing == null);
            } catch (RollerException e) {
                log.error("Error checking group existence", e);
                createNew = true;
            }
        }
        model.addAttribute("createNew", createNew);

        // build subscriptions list (excluding internal subs)
        List<Subscription> subs = Collections.emptyList();
        if (group != null && group.getSubscriptions() != null) {
            subs = new ArrayList<>();
            for (Subscription sub : group.getSubscriptions()) {
                if (!sub.getFeedURL().startsWith("weblogger:")) {
                    subs.add(sub);
                }
            }
        }
        model.addAttribute("subscriptions", subs);

        // compute page title
        String pageTitle;
        if (createNew) {
            pageTitle = getText("planetGroupSubs.custom.title.new", request);
        } else if (group != null && "all".equals(group.getHandle())) {
            pageTitle = getText("planetGroupSubs.default.title", request);
        } else {
            String handle = group != null ? group.getHandle() : "";
            pageTitle = getText("planetGroupSubs.custom.title",
                    new Object[]{handle}, request);
        }
        model.addAttribute("pageTitle", pageTitle);
    }

    private Planet getPlanet() {
        try {
            PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
            return pmgr.getWeblogger(DEFAULT_PLANET_HANDLE);
        } catch (Exception ex) {
            log.error("Error loading weblogger planet - " + DEFAULT_PLANET_HANDLE, ex);
        }
        return null;
    }

    static PlanetGroup getGroupFromRequest(HttpServletRequest request, Planet planet) {
        PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
        PlanetGroup planetGroup = null;
        try {
            if (request.getParameter("group.id") != null) {
                String groupId = request.getParameter("group.id");
                planetGroup = pmgr.getGroupById(groupId);
            } else if (request.getParameter("group.handle") != null) {
                String groupHandle = request.getParameter("group.handle");
                planetGroup = pmgr.getGroup(planet, groupHandle);
            } else {
                planetGroup = pmgr.getGroup(planet, "all");
            }
        } catch (Exception ex) {
            log.error("Error looking up planet group", ex);
        }
        return planetGroup;
    }

    private PlanetGroup getGroupById(String groupId) {
        try {
            PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
            return pmgr.getGroupById(groupId);
        } catch (Exception ex) {
            log.error("Error getting group by id: " + groupId, ex);
        }
        return null;
    }

    private PlanetGroup getGroupByHandle(Planet planet, String handle) {
        try {
            PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
            return pmgr.getGroup(planet, handle);
        } catch (Exception ex) {
            log.error("Error getting group by handle: " + handle, ex);
        }
        return null;
    }
}
