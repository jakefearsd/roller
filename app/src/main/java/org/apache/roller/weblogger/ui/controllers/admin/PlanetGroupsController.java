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

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.planet.business.PlanetManager;
import org.apache.roller.planet.pojos.Planet;
import org.apache.roller.planet.pojos.PlanetGroup;
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
 * Manage planet groups.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class PlanetGroupsController extends BaseController {

    private static final Log log = LogFactory.getLog(PlanetGroupsController.class);

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
    public String getPageTitle() {
        return "planetGroups.pagetitle";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "planetGroups";
    }

    /**
     * Show planet groups page.
     */
    @GetMapping("/planetGroups.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("groups", getGroups());
        return ".PlanetGroups";
    }

    /**
     * Delete group.
     */
    @PostMapping("/planetGroups!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @RequestParam(value = "group.handle", required = false) String groupHandle) {
        populateCommonModel(request, model);

        Planet planet = getPlanet();
        PlanetGroup group = null;
        if (planet != null && groupHandle != null) {
            group = PlanetGroupSubsController.getGroupFromRequest(request, planet);
        }

        if (group != null) {
            try {
                PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
                pmgr.deleteGroup(group);
                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "planetSubscription.success.deleted", request);
            } catch (Exception ex) {
                log.error("Error deleting planet group - " + (group.getId() != null ? group.getId() : ""));
                addError(model, "generic.error.check.logs", request);
            }
        }

        model.addAttribute("groups", getGroups());
        return ".PlanetGroups";
    }

    // --- Helpers ---

    private Planet getPlanet() {
        try {
            PlanetManager pmgr = WebloggerFactory.getWeblogger().getPlanetManager();
            return pmgr.getWeblogger(DEFAULT_PLANET_HANDLE);
        } catch (Exception ex) {
            log.error("Error loading weblogger planet - " + DEFAULT_PLANET_HANDLE, ex);
        }
        return null;
    }

    private List<PlanetGroup> getGroups() {
        List<PlanetGroup> displayGroups = new ArrayList<>();
        Planet planet = getPlanet();
        if (planet != null) {
            for (PlanetGroup planetGroup : planet.getGroups()) {
                // The "all" group is considered a special group and cannot be managed independently
                if (!planetGroup.getHandle().equals("all")) {
                    displayGroups.add(planetGroup);
                }
            }
        }
        return displayGroups;
    }
}
