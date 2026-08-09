<%--
  Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  The ASF licenses this file to You
  under the Apache License, Version 2.0 (the "License"); you may not
  use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.  For additional information regarding
  copyright in this work, please see the NOTICE file in the top level
  directory of this distribution.
--%>
<%-- Container-level error page (registered on Exception.class/
     HttpStatus.INTERNAL_SERVER_ERROR in boot/WebContainerConfig.java). See
     404.jsp's comment for why this is hardcoded English with inline CSS
     rather than <fmt:message>/<spring:message> and an external stylesheet.
     No exception detail is rendered here on purpose: this is the page shown
     for an *unexpected* server error, and neither the exception message nor
     its class name is for a reader to see -- the real detail goes to the
     server log (see RollerHandlerInterceptor / the container's own error
     logging), not the response body. --%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<!doctype html>
<html lang="en">
    <head>
        <meta charset="utf-8">
        <meta http-equiv="X-UA-Compatible" content="IE=edge">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Something went wrong</title>
        <style>
            :root { color-scheme: light dark; }
            body {
                margin: 0;
                padding: 4rem 1.5rem;
                background: #f5f5f5;
                color: #222;
                font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                text-align: center;
            }
            .box {
                max-width: 32rem;
                margin: 0 auto;
            }
            .code {
                font-size: 0.9rem;
                letter-spacing: 0.08em;
                text-transform: uppercase;
                color: #888;
                margin: 0 0 0.5rem;
            }
            h1 {
                font-size: 1.6rem;
                margin: 0 0 0.75rem;
            }
            p {
                color: #555;
                line-height: 1.5;
            }
            a {
                color: #06c;
            }
        </style>
    </head>
    <body>
        <div class="box">
            <p class="code">500</p>
            <h1>Something went wrong</h1>
            <p>Roller has encountered an unexpected error and logged it. Please try again, and
               contact your site administrator if the problem continues.</p>
            <p><a href="<c:url value="/"/>">Return to the home page</a></p>
        </div>
    </body>
</html>
