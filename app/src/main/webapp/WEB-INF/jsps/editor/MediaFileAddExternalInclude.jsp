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
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<p class="subtitle">
    <spring:message code="mediaFile.add.title"/>
</p>

<script>
function submitPage(frm) {
    var filePointer = "<a href='" + frm.url.value + "'>" + frm.title.value + "</a>";
    parent.onClose(filePointer);
}
</script>

<form id="entry" action="#" method="POST">
<table class="entryEditTable" cellpadding="0" cellspacing="0" width="100%">

        <tr>
            <td class="entryEditFormLabel">
                <label for="status">URL:</label>
            </td>
            <td>
                <input type="text" name="url" value="${url}" size="50" maxlength="255" tabindex="1" class="form-control"/>
            </td>
       </tr>

       <tr>
            <td class="entryEditFormLabel">
                <label for="status">Title</label>
            </td>
            <td>
                <input type="text" name="title" value="${title}" size="50" maxlength="255" class="form-control"/>
            </td>
       </tr>

    </table>

    <br>
    <div class="control">
       <input type="button" value="Insert" name="submit" onclick="submitPage(this.form)" />
       <input type="button" value="Cancel" onClick="javascript:window.parent.onClose();" />
    </div>

<sec:csrfInput/>
</form>
