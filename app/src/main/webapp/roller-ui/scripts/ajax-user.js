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

// Used in: UserAdmin.jsp

// NOTE: this file is pulled in by a JSP include DIRECTIVE (translation time),
// so the JSP scriptlet below IS interpolated. It is not a static resource
// despite the .js extension -- do not "clean it up" into one.
var http = new XMLHttpRequest();
var init = false;
var isBusy = false;
var userURL = "<%= request.getContextPath() %>" + "/roller-ui/authoring/userdata?length=50";

/*
 * Submit is available exactly when the username box holds something.
 *
 * It used to be driven the other way round: focusing the box DISABLED the
 * button, and only picking an entry out of the list re-enabled it. So an
 * administrator who typed a username -- the obvious thing to do with a text
 * box next to a button marked Edit -- was left with the button greyed out no
 * matter what they typed, and the only way through was to click the list.
 *
 * The null guard is defensive: UserAdmin.jsp is this script's only consumer
 * now and always has a #user-submit, but nothing enforces that at compile
 * time.
 */
function updateUserSubmitState() {
    var userSubmitButton = document.getElementById("user-submit");
    if (!userSubmitButton) return;
    var userName = document.getElementById("userName");
    userSubmitButton.disabled = !userName || userName.value.trim().length === 0;
}

function onUserNameFocus(enabled) {
    if (!init) {
        init = true;
        var u = userURL;
        if (enabled != null) u = u + "&enabled=" + enabled;
        sendUserRequest(u);
    }
    updateUserSubmitState();
}

function onUserNameChange(enabled) {
    var u = userURL;
    if (enabled != null) u = u + "&enabled=" + enabled;
    var userName = document.getElementById("userName");
    if (userName.value.length > 0) u = u + "&startsWith=" + userName.value;
    sendUserRequest(u);
    updateUserSubmitState();
}

function onUserSelected() {
    var userList = document.getElementById("userList");
    var user = userList.options[userList.options.selectedIndex];
    var userName = document.getElementById("userName");
    userName.value = user.value;

    updateUserSubmitState();
}

function sendUserRequest(url) {
    if (isBusy) return;
    isBusy = true;
    http.open('get', url);
    http.onreadystatechange = handleUserResponse;
    http.send(null);
}

function handleUserResponse() {
    if (http.readyState === 4) {
        var userList = document.getElementById("userList");
        for (let i = userList.options.length; i >= 0; i--) {
            userList.options[i] = null;
        }
        var data = http.responseText;
        if (data.indexOf("\n") !== -1) {
            var lines = data.split('\n');
            for (let i = 0; i < lines.length; i++) {
                if (lines[i].indexOf(',') !== -1) {
                   var userArray = lines[i].split(',');
                   userList.options[userList.length] =
                      new Option(userArray[0] + " (" + userArray[1] + ")", userArray[0]);
                }
            }
        }

    }
    isBusy = false;
}
