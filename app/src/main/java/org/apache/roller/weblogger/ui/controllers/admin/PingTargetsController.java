/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.pings.PingTargetManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.PingTarget;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Admin action for managing global ping targets.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class PingTargetsController extends BaseController {

    private static final Log log = LogFactory.getLog(PingTargetsController.class);

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
        return "commonPingTargets.commonPingTargets";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "commonPingTargets";
    }

    /**
     * Display the ping targets.
     */
    @GetMapping("/commonPingTargets.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadPingTargets(model);
        return ".PingTargets";
    }

    /**
     * Enable a ping target (set auto-enabled to true).
     */
    @GetMapping("/commonPingTargets!enable.rol")
    public String enable(HttpServletRequest request, Model model,
                         @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = loadPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                pingTarget.setAutoEnabled(true);
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTargetMgr.savePingTarget(pingTarget);
                WebloggerFactory.getWeblogger().flush();
            } catch (Exception ex) {
                log.error("Error saving ping target", ex);
                addError(model, "commonPingTargets.error.saving", request);
            }
        } else {
            addError(model, "commonPingTargets.error.enabling", request);
        }

        loadPingTargets(model);
        return ".PingTargets";
    }

    /**
     * Disable a ping target (set auto-enabled to false).
     */
    @GetMapping("/commonPingTargets!disable.rol")
    public String disable(HttpServletRequest request, Model model,
                          @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = loadPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                pingTarget.setAutoEnabled(false);
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTargetMgr.savePingTarget(pingTarget);
                WebloggerFactory.getWeblogger().flush();
            } catch (Exception ex) {
                log.error("Error saving ping target", ex);
                addError(model, "commonPingTargets.error.saving", request);
            }
        } else {
            addError(model, "commonPingTargets.error.disabling", request);
        }

        loadPingTargets(model);
        return ".PingTargets";
    }

    /**
     * Delete a ping target.
     */
    @PostMapping("/commonPingTargets!delete.rol")
    public String delete(HttpServletRequest request, Model model,
                         @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = loadPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                pingTargetMgr.removePingTarget(pingTarget);
                WebloggerFactory.getWeblogger().flush();
                addMessage(model, "pingTarget.deleted", pingTarget.getName(), request);
            } catch (WebloggerException ex) {
                log.error("Error deleting ping target - " + pingTargetId, ex);
                addError(model, "generic.error.check.logs", request);
            }
        } else {
            addError(model, "pingTarget.notFound", request);
        }

        loadPingTargets(model);
        return ".PingTargets";
    }

    private void loadPingTargets(Model model) {
        List<PingTarget> pingTargets = Collections.emptyList();
        try {
            PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
            pingTargets = pingTargetMgr.getCommonPingTargets();
        } catch (WebloggerException ex) {
            log.error("Error loading common ping targets", ex);
        }
        model.addAttribute("pingTargets", pingTargets);
    }

    private PingTarget loadPingTarget(String pingTargetId) {
        if (!StringUtils.isEmpty(pingTargetId)) {
            try {
                PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();
                return pingTargetMgr.getPingTarget(pingTargetId);
            } catch (WebloggerException ex) {
                log.error("Error looking up ping target - " + pingTargetId, ex);
            }
        }
        return null;
    }
}
