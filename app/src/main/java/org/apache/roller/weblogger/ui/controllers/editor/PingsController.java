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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.PingConfig;
import org.apache.roller.weblogger.business.pings.AutoPingManager;
import org.apache.roller.weblogger.business.pings.PingTargetManager;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.pojos.AutoPing;
import org.apache.roller.weblogger.pojos.PingTarget;
import org.apache.roller.weblogger.business.pings.WeblogUpdatePinger;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.xmlrpc.XmlRpcException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Actions for setting up automatic ping configuration for a weblog.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class PingsController extends BaseController {

    private static final Log log = LogFactory.getLog(PingsController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "pings";
    }

    @Override
    public String getPageTitle() {
        return "pings.title";
    }

    @GetMapping("/pings.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadPingData(request, model);
        return ".Pings";
    }

    @PostMapping("/pings!enable.rol")
    public String enable(HttpServletRequest request, Model model,
                         @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = lookupPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                AutoPingManager autoPingMgr = WebloggerFactory.getWeblogger().getAutopingManager();
                AutoPing autoPing = new AutoPing(null, pingTarget, getActionWeblog(request));
                autoPingMgr.saveAutoPing(autoPing);
                WebloggerFactory.getWeblogger().flush();
            } catch (Exception ex) {
                log.error("Error saving auto ping for target - " + pingTargetId, ex);
                addError(model, "Error enabling auto ping", request);
            }
        }

        loadPingData(request, model);
        return ".Pings";
    }

    @PostMapping("/pings!disable.rol")
    public String disable(HttpServletRequest request, Model model,
                          @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = lookupPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                AutoPingManager autoPingMgr = WebloggerFactory.getWeblogger().getAutopingManager();
                autoPingMgr.removeAutoPing(pingTarget, getActionWeblog(request));
                WebloggerFactory.getWeblogger().flush();
            } catch (Exception ex) {
                log.error("Error removing auto ping for target - " + pingTargetId, ex);
                addError(model, "Error disabling auto ping", request);
            }
        }

        loadPingData(request, model);
        return ".Pings";
    }

    @PostMapping("/pings!pingNow.rol")
    public String pingNow(HttpServletRequest request, Model model,
                          @RequestParam(value = "pingTargetId", required = false) String pingTargetId) {
        populateCommonModel(request, model);

        PingTarget pingTarget = lookupPingTarget(pingTargetId);
        if (pingTarget != null) {
            try {
                if (PingConfig.getSuspendPingProcessing()) {
                    addError(model, "ping.pingProcessingIsSuspended", request);
                } else {
                    WeblogUpdatePinger.PingResult pingResult =
                            WeblogUpdatePinger.sendPing(pingTarget, getActionWeblog(request));
                    if (pingResult.isError()) {
                        if (pingResult.getMessage() != null && !pingResult.getMessage().isBlank()) {
                            addError(model, "ping.transmittedButError", request);
                        } else {
                            addError(model, "ping.transmissionFailed", request);
                        }
                    } else {
                        addMessage(model, "ping.successful", request);
                    }
                }
            } catch (IOException ex) {
                log.debug(ex);
                addError(model, "ping.transmissionFailed", request);
                addSpecificMessages(ex, model, request);
            } catch (XmlRpcException ex) {
                log.debug(ex);
                addError(model, "ping.transmissionFailed", request);
                addSpecificMessages(ex, model, request);
            }
        }

        loadPingData(request, model);
        return ".Pings";
    }

    private void addSpecificMessages(Exception ex, Model model, HttpServletRequest request) {
        if (ex instanceof UnknownHostException) {
            addError(model, "ping.unknownHost", request);
        } else if (ex instanceof SocketException) {
            addError(model, "ping.networkConnectionFailed", request);
        }
    }

    private void loadPingData(HttpServletRequest request, Model model) {
        PingTargetManager pingTargetMgr = WebloggerFactory.getWeblogger().getPingTargetManager();

        try {
            model.addAttribute("commonPingTargets", pingTargetMgr.getCommonPingTargets());
        } catch (WebloggerException ex) {
            log.error("Error loading ping target lists", ex);
        }

        // build enabled map
        AutoPingManager autoPingMgr = WebloggerFactory.getWeblogger().getAutopingManager();
        Map<String, Boolean> isEnabled = new HashMap<>();

        List<AutoPing> autoPings = Collections.emptyList();
        try {
            autoPings = autoPingMgr.getAutoPingsByWebsite(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error looking up auto pings", ex);
        }

        for (AutoPing autoPing : autoPings) {
            isEnabled.put(autoPing.getPingTarget().getId(), Boolean.TRUE);
        }

        @SuppressWarnings("unchecked")
        List<PingTarget> commonPingTargets = (List<PingTarget>) model.getAttribute("commonPingTargets");
        if (commonPingTargets != null) {
            for (PingTarget pt : commonPingTargets) {
                if (isEnabled.get(pt.getId()) == null) {
                    isEnabled.put(pt.getId(), Boolean.FALSE);
                }
            }
        }

        model.addAttribute("pingStatus", isEnabled);
    }

    private PingTarget lookupPingTarget(String id) {
        if (id == null) {
            return null;
        }
        try {
            return WebloggerFactory.getWeblogger().getPingTargetManager().getPingTarget(id);
        } catch (WebloggerException ex) {
            log.error("Error looking up ping target - " + id, ex);
        }
        return null;
    }
}
