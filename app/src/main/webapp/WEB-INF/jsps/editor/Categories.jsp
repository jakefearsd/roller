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
    <spring:message code="categoriesForm.subtitle" arguments="${actionWeblog.handle}"/>
</p>
<p class="pagetip">
    <spring:message code="categoriesForm.rootPrompt"/>
</p>

<%-- Form is a table of categories each with checkbox --%>
<form action="${pageContext.request.contextPath}/roller-ui/authoring/categories!move.rol" method="post">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
    <input type="hidden" name="categoryId" value="${categoryId}"/>

    <table class="rollertable table table-striped" width="100%">

        <tr class="rollertable">
            <th width="30%"><spring:message code="generic.name"/></th>
            <th width="50%"><spring:message code="generic.description"/></th>
            <th width="10%"><spring:message code="generic.edit"/></th>
            <th width="10%"><spring:message code="categoriesForm.remove"/></th>
        </tr>

        <c:choose>
<c:when test="${not empty allCategories}">

            <c:forEach items="${allCategories}" var="category" varStatus="rowstatus">
                <tr>
                    <td>${category.name}</td>

                    <td>${category.description}</td>

                    <td align="center">

                        <c:set var="categoryId" value="${category.id}"/>
                        <c:set var="categoryName" value="${category.name}"/>
                        <c:set var="categoryDesc" value="${category.description}"/>
                        <c:set var="categoryImage" value="${category.image}"/>
                        <a href="#" onclick="showCategoryEditModal(
                                '${categoryId}',
                                '${categoryName}',
                                '${categoryDesc}',
                                '${categoryImage}' )">
                            <span class="glyphicon glyphicon-edit"></span>
                        </a>

                    </td>

                    <td class="rollertable" align="center">
                        <c:if test="${allCategories.size() > 1}">

                            <c:set var="categoryId" value="${category.id}"/>
                            <c:set var="categoryName" value="${category.name}"/>
                            <c:set var="categoryInUse" value="${category.inUse.toString()}"/>
                            <a href="#" onclick="showCategoryDeleteModal(
                                    '${categoryId}',
                                    '${categoryName}',
                                    ${categoryInUse} )" >
                                <span class="glyphicon glyphicon-trash"></span>
                            </a>

                        </c:if>
                    </td>

                </tr>
            </c:forEach>

        </c:when>
<c:otherwise>
            <tr>
                <td style="vertical-align:middle" colspan="6"><spring:message code="categoriesForm.noresults"/></td>
            </tr>
        </c:otherwise>
</c:choose></table>

<sec:csrfInput/>
</form>


<%-- ============================================================= --%>
<%-- add/edit category modal --%>

<div id="category-edit-modal" class="modal fade category-edit-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <h3 id="category-edit-title"></h3>
            </div>

            <div class="modal-body">
                <form id="categoryEditForm" action="${pageContext.request.contextPath}/roller-ui/authoring/categoryEdit.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                    <input type="hidden" name="bean.id" value="${bean.id}"/>

                    <div class="form-group">
                        <label class="col-sm-3 control-label"><spring:message code="generic.name"/></label>
                        <div class="col-sm-9">
                            <input type="text" name="bean.name" value="${bean.name}" maxlength="255" class="form-control" onchange="validateCategory()" onkeyup="validateCategory()"/>
                        </div>
                    </div>

                    <div class="form-group">
                        <label class="col-sm-3 control-label"><spring:message code="generic.description"/></label>
                        <div class="col-sm-9">
                            <input type="text" name="bean.description" value="${bean.description}" class="form-control"/>
                        </div>
                    </div>

                    <div class="form-group">
                        <label class="col-sm-3 control-label"><spring:message code="categoryForm.image"/></label>
                        <div class="col-sm-9">
                            <input type="text" name="bean.image" value="${bean.image}" class="form-control" onchange="validateCategory()" onkeyup="validateCategory()"/>
                        </div>
                    </div>
                <sec:csrfInput/>
</form>
            </div>

            <div class="modal-footer">
                <p id="feedback-area-edit"></p>
                <button onclick="submitEditedCategory()" class="btn btn-primary">
                    <spring:message code="generic.save"/>
                </button>
                <button type="button" class="btn" data-dismiss="modal">
                    <spring:message code="generic.cancel"/>
                </button>
            </div>

        </div>
    </div>
</div>

<script>

    var feedbackAreaEdit = $("#feedback-area-edit");

    function showCategoryEditModal( id, name, desc, image ) {
        feedbackAreaEdit.html("");
        $('#category-edit-title').html('<spring:message code="categoryForm.edit.title"/>');

        $('#categoryEditForm_bean_id').val(id);
        $('#categoryEditForm_bean_name').val(name);
        $('#categoryEditForm_bean_description').val(desc);
        $('#categoryEditForm_bean_image').val(image);

        $('#category-edit-modal').modal({show: true});

    }

    function validateCategory() {

        var saveCategoryButton = $('#categoryEditForm:first');

        var categoryName = $("#categoryEditForm_bean_name").val();
        var imageURL = $("#categoryEditForm_bean_image").val();

        if (!categoryName || categoryName.trim() === '') {
            saveCategoryButton.attr("disabled", true);
            feedbackAreaEdit.html('<spring:message code="categoryForm.requiredFields"/>');
            feedbackAreaEdit.css("color", "red");
            return;
        }

        if (imageURL && imageURL.trim() !== '') {
            if (!isValidUrl(imageURL)) {
                saveCategoryButton.attr("disabled", true);
                feedbackAreaEdit.html('<spring:message code="categoryForm.badURL"/>');
                feedbackAreaEdit.css("color", "red");
                return;
            }
        }

        feedbackAreaEdit.html('');
        saveCategoryButton.attr("disabled", false);
    }

    function submitEditedCategory() {

        // if name is empty reject and show error message
        if ($("#categoryEditForm_bean_name").val().trim() === "") {
            feedbackAreaEdit.html('<spring:message code="categoryForm.requiredFields"/>');
            feedbackAreaEdit.css("color", "red");
            return;
        }

        // post category via AJAX
        $.ajax({
            method: 'post',
            url: "${pageContext.request.contextPath}/roller-ui/authoring/categoryEdit!save.rol",
            data: $("#categoryEditForm").serialize(),
            context: document.body

        }).done(function (data) {

            // kludge: scrape response status from HTML returned by Struts
            var alertEnd = data.indexOf("ALERT_END");
            var notUnique = data.indexOf('<spring:message code="categoryForm.error.duplicateName"/>');
            var notValid = data.indexOf('<spring:message code="categoryForm.error.invalidName"/>');
            if (notUnique > 0 && notUnique < alertEnd) {
                feedbackAreaEdit.css("color", "red");
                feedbackAreaEdit.html('<spring:message code="categoryForm.error.duplicateName"/>');
            } else if (notValid > 0 && notValid < alertEnd) {
                feedbackAreaEdit.css("color", "red");
                feedbackAreaEdit.html('<spring:message code="categoryForm.error.invalidName"/>');
            } else {
                feedbackAreaEdit.css("color", "green");
                feedbackAreaEdit.html('<spring:message code="generic.success"/>');
                $('#category-edit-modal').modal("hide");
                location.reload(true);
            }

        }).error(function (data) {
            feedbackAreaEdit.html('<spring:message code="generic.error.check.logs"/>');
            feedbackAreaEdit.css("color", "red");
        });
    }

</script>


<%-- ============================================================= --%>
<%-- delete confirmation modal --%>

<div id="delete-category-modal" class="modal fade delete-category-modal" tabindex="-1" role="dialog">

    <div class="modal-dialog modal-lg">

        <div class="modal-content">

            <div class="modal-header">
                <h3>
                    <spring:message code="categoryDeleteOK.removeCategory"/>:
                    <span id="category-name"></span>
                </h3>
            </div>

            <form action="${pageContext.request.contextPath}/roller-ui/authoring/categoryRemove!remove.rol" method="post" class="form-horizontal">
<input type="hidden" name="weblog" value="${actionWeblog.handle}"/>
                <input type="hidden" name="removeId" value="${removeId}"/>
                
                <div class="modal-body">

                    <div id="category-in-use" style="display:none">
                        <p>
                            <spring:message code="categoryDeleteOK.warningCatInUse"/>
                            <spring:message code="categoryDeleteOK.youMustMoveEntries"/>
                        </p>
                        <spring:message code="categoryDeleteOK.moveToWhere"/>
                        <select name="targetCategoryId" class="form-control">
<c:forEach items="${allCategories}" var="opt">
<option value="${opt.id}" ${opt.id == targetCategoryId ? 'selected' : ''}>${opt.name}</option>
</c:forEach>
</select>
                    </div>

                    <div id="category-empty" style="display:none">
                        <p><spring:message code="categoryDeleteOK.noEntriesInCat"/></p>
                    </div>
                    
                    <p> <strong><spring:message code="categoryDeleteOK.areYouSure"/></strong> </p>
                </div>

                <div class="modal-footer">
                    <button type="submit" class="btn btn-danger"><spring:message code="generic.yes"/></button>&nbsp;
                    <button type="button" class="btn btn-default" data-dismiss="modal">
                        <spring:message code="generic.no"/>
                    </button>
                </div>

            <sec:csrfInput/>
</form>

        </div>
    </div>
</div>

<script>

    function showCategoryDeleteModal( id, name, inUse ) {
        $('#categoryRemove_removeId').val(id);
        $('#categoryEdit_bean_name').val(name);
        $('#category-name').html(name);
        if ( inUse ) {
            $('#category-in-use').css('display','block');
            $('#category-emtpy').css('display', 'none');
        } else {
            $('#category-in-use').css('display', 'none');
            $('#category-emtpy').css('display', 'block');
        }
        populateCategorySelect(id);
        $('#delete-category-modal').modal({show: true});
    }

    function populateCategorySelect(removeId) {
        const allCategories = [];

        <c:forEach items="${allCategories}" var="category">
        allCategories.push({
            id: '${category.id}',
            name: '${category.name}'
        });
        </c:forEach>

        const select = $('#categoryRemove_targetCategoryId');
        select.empty();
        allCategories.forEach(function(category) {
            if (category.id !== removeId) {
                select.append(new Option(category.name, category.id));
            }
        });
    }

</script>
