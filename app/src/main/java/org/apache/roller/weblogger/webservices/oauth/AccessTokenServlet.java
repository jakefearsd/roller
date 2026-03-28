/*
 * Copyright 2007 AOL, LLC.
 * Portions Copyright 2009 Apache Software Foundation
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

package org.apache.roller.weblogger.webservices.oauth;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Access Token request handler
 *
 * @author Praveen Alavilli
 * @author Dave Johnson (adapted for Roller)
 */
public class AccessTokenServlet extends HttpServlet {
    protected static final Log log = LogFactory.getFactory().getInstance(AccessTokenServlet.class);
    
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        processRequest(request, response);
    }
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        processRequest(request, response);
    }
        
    public void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        // OAuth 1.0a support removed: the net.oauth library uses javax.servlet
        // which is incompatible with Jakarta EE (jakarta.servlet).
        throw new UnsupportedOperationException(
                "OAuth 1.0a support removed during Jakarta EE migration");
    }

}
