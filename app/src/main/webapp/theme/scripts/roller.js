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
/* This function is used to set cookies */
function setCookie(name, value, expires, path, domain, secure=true, sameSite=true) {
  document.cookie = name + "=" + escape (value) +
    ((expires) ? "; expires=" + expires.toGMTString() : "") +
    ((path) ? "; path=" + path : "") +
    ((domain) ? "; domain=" + domain : "") + ((secure) ? "; secure" : "") +
    ((sameSite) ? "; SameSite=Strict" : "");
}

/* This function is used to get cookies */
function getCookie(name) {
	var prefix = name + "=";
	var start = document.cookie.indexOf(prefix);

	if (start===-1) {
		return null;
	}

	var end = document.cookie.indexOf(";", start+prefix.length);
	if (end===-1) {
		end=document.cookie.length;
	}

	var value=document.cookie.substring(start+prefix.length, end);
	return unescape(value);
}

/* This function is used to delete cookies */
function deleteCookie(name,path,domain) {
  if (getCookie(name)) {
    document.cookie = name + "=" +
      ((path) ? "; path=" + path : "") +
      ((domain) ? "; domain=" + domain : "") +
      "; expires=Thu, 01-Jan-70 00:00:01 GMT";
  }
}

/* This function is used to show/hide elements with a display:none style attribute */
function toggle(targetId) {
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.style.display === "none") {
    		target.style.display = "";
    	} else {
    		target.style.display = "none";
    	}
    }
}

/* The toggleFolder and togglePlusMinus functions are for expanding/contracting folders */
function toggleFolder(targetId) {
    var expanded;
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.style.display === "none") {
    		target.style.display = "";
            expanded = true;
    	} else {
    		target.style.display = "none";
            expanded = false;
    	}
        togglePlusMinus("i" + targetId);

        // set a cookie to remember this preference
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 365 * 60 * 60 * 1000); // sets it for approx 365 days.
        setCookie("rfolder-"+targetId,expanded,expires,"/");
    }
}

function togglePlusMinus(targetId) {
    if (document.getElementById) {
        target = document.getElementById(targetId);
    	if (target.innerHTML === "+") {
    		target.innerHTML = "-";
    	} else {
    		target.innerHTML = "+";
    	}
    }
}

/* This function is to set folders to expand/contract based on a user's preference */
function folderPreference(folderId) {
    var folderCookie = getCookie("rfolder-"+folderId);
    if (folderCookie != null) { // we have user's last setting
        var folder = document.getElementById(folderId);
        var plusMinus = document.getElementById("i"+folderId);
        if (folderCookie === "true") { // show
            folder.style.display = "";
            plusMinus.innerHTML = "-";
        } else { // hide
            folder.style.display = "none";
            plusMinus.innerHTML = "+";
        }
    }
}

function toggleNextRow(e) {
    var checked;
    if (e.type === "checkbox") {
        checked = e.checked;
    } else if (e.type === "radio") {
        var v = e.value;
        checked = (v === "1" || v === "y" || v === "true");
    }
    // var nextRow = e.parentNode.parentNode.nextSibling;
    // the above doesn't work on Mozilla since it treats white space as nodes
    var thisRow = e.parentNode.parentNode;
    var tableBody = thisRow.parentNode;
    var nextRow = tableBody.getElementsByTagName("tr")[thisRow.rowIndex+1];

    if (checked === true) {
        nextRow.style.display = "";
    } else {
        nextRow.style.display = "none";
    }
}

function toggleControl(toggleId, targetId) {
    var expanded;
    if (document.getElementById) {
        target = document.getElementById(targetId);
        toggle = document.getElementById(toggleId);
    	if (target.style.display === "none") {
    		target.style.display = "";
            expanded = true;

    	} else {
    		target.style.display = "none";
            expanded = false;
    	}
        togglePlusMinus("i" + targetId);

        // set a cookie to remember this preference
        var expires = new Date();
        expires.setTime(expires.getTime() + 24 * 365 * 60 * 60 * 1000); // sets it for approx 365 days.
        setCookie("control_"+targetId,expanded,expires,"/");
    }
}

function isblank(s) {
   for (var i=0; i<s.length; s++) {
      var c = s.charAt(i);
      if ((c!==' ') && (c!=='\n') && (c!=='')) return false;
   }
    return true;
}

// Show the document's title on the status bar
window.defaultStatus=document.title;

// Toggle check boxes
function toggleFunctionAll(toggleValue) {
	var inputs = document.getElementsByTagName('input');
	for(var i = 0; i < inputs.length ; i++) {
		if(inputs[i].name !== "control" && inputs[i].type === 'checkbox' && inputs[i].disabled === false ) {
			if (inputs[i].checked === true){
				inputs[i].checked = !inputs[i].checked;
			} else{
				inputs[i].checked = toggleValue;
			}
		}
	}
}

function toggleFunction(toggleValue,name) {
	var inputs = document.getElementsByName(name);
	for(var i = 0; i < inputs.length ; i++) {
		if(inputs[i].type === 'checkbox' && inputs[i].disabled === false) {
           inputs[i].checked = toggleValue;
		}
	}
}

function isValidUrl(url) {
    return /^(http|https|ftp):\/\/[a-z0-9]+([\-\.]{1}[a-z0-9]+)*\.[a-z]{2,5}(:[0-9]{1,5})?(\/.*)?$/i.test(url);
}

function validateEmail(email) {
    var re = /^(([^<>()\[\]\\.,;:\s@"]+(\.[^<>()\[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
    return re.test(email);
}
// This file is also loaded on public weblog pages (see the comment form macro
// in weblog.vm) purely for the cookie/validateComments helpers above, and those
// pages never load jQuery or jquery-validate. Guard the jQuery-validate wiring
// below -- which only targets the admin "validate-form"/"validate-email"
// classes (see CreateWeblog.jsp) -- so it does not throw on pages without jQuery.
if (typeof jQuery !== "undefined") {
    $(document).ready(function () {
        jQuery("form.validate-form").validate();
        // Email fields get jquery-validate's own email rule and nothing more.
        //
        // There used to be an extra regex here requiring the top-level domain
        // to be 2-4 characters. That silently rejected every address at a
        // modern TLD -- .photography, .gallery, .travel, .studio, .email --
        // and there was no way for the person typing to tell why, because the
        // message just said the address was invalid. It also blocked the whole
        // form: jquery-validate refuses to submit, so a weblog simply could
        // not be created with such an address.
        jQuery( ".validate-email" ).rules( "add", {
            minlength: 3,
            maxlength: 255,
            email: true
        });
    });
}

/*
 * Confirmation prompts, driven by a data-confirm attribute rather than an
 * inline onclick/onsubmit handler.
 *
 * THE INLINE FORM FAILS OPEN, WHICH IS WHY IT IS BANNED HERE. Writing
 * onclick="return confirm('${fn:escapeXml(msg)}')" puts an HTML escape into a
 * JS-string position: the HTML parser decodes &#039; back to a literal
 * apostrophe BEFORE the JS is compiled, so one apostrophe anywhere in the
 * message -- a translated value, or an address like o'brien@example.com --
 * terminates the string, the handler fails to compile, and the click proceeds
 * WITH NO CONFIRMATION AT ALL. The destructive action just happens.
 *
 * In an attribute value there is no second parser: fn:escapeXml is the
 * correct escape for that position, the browser hands dataset.confirm the
 * exact literal text, and quotes and apostrophes are simply characters.
 *
 * Both events are handled, and THE DIVISION BETWEEN THEM IS LOAD-BEARING: a
 * click prompt and a submit prompt for the same action means two dialogs for
 * one click, and the second one reads like a bug to the operator.
 *
 *   - click  owns data-confirm on a CONTROL: the button that routes by
 *            formaction (Maintenance), a link, anything inside a form.
 *   - submit owns data-confirm on the FORM itself, which is the only way to
 *            catch a submit with no click behind it (Enter in a text field).
 *
 * The boundary is enforced in one place: the click handler's walk up the
 * ancestors STOPS at the form. Without that stop it finds a form-level
 * attribute, prompts, allows the native submit, and the submit handler --
 * seeing the same attribute -- prompts again. That is not hypothetical; it
 * shipped for one round on UserEdit's send-password-link form.
 *
 * Capture phase, so this runs before any other handler commits to the action.
 */
(function () {
    "use strict";

    function confirmed(element) {
        var message = element.getAttribute("data-confirm");
        return !message || window.confirm(message);
    }

    // Deliberately stops BEFORE the form: a form's own data-confirm belongs to
    // the submit handler, and answering it here too would prompt twice.
    function nearestConfirmableControl(node) {
        while (node && node.nodeType === 1 && node.tagName !== "FORM") {
            if (node.hasAttribute && node.hasAttribute("data-confirm")) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    document.addEventListener("click", function (event) {
        var target = nearestConfirmableControl(event.target);
        if (target && !confirmed(target)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);

    document.addEventListener("submit", function (event) {
        // Only the form's OWN attribute, never a descendant's: a control's
        // prompt was already answered by the click handler above, which is
        // why that handler stops at the form and this one does not climb.
        var form = event.target;
        if (form.hasAttribute && form.hasAttribute("data-confirm") && !confirmed(form)) {
            event.preventDefault();
            event.stopPropagation();
        }
    }, true);
})();
