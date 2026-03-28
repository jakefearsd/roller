<%-- 
This default stuff goes in the HTML head element of each page
You can override it with your own file via WEB-INF/tiles-def.xml
--%>

<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<script src="<c:url value='/webjars/jquery/3.7.1/jquery.min.js'/>"></script>

<script src="<c:url value='/webjars/jquery-ui/1.14.1/jquery-ui.min.js'/>"></script>
<link href="<c:url value='/webjars/jquery-ui/1.14.1/jquery-ui.css'/>" rel="stylesheet" />

<script src="<c:url value='/webjars/jquery-validation/1.20.0/jquery.validate.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap/3.4.1/css/bootstrap.min.css'/>" rel="stylesheet" />
<link href="<c:url value='/webjars/bootstrap/3.4.1/css/bootstrap-theme.min.css'/>" rel="stylesheet" />
<script src="<c:url value='/webjars/bootstrap/3.4.1/js/bootstrap.min.js'/>"></script>

<script src="<c:url value='/webjars/clipboard.js/2.0.11/clipboard.min.js'/>"></script>

<script src="<c:url value='/webjars/summernote/0.8.12/dist/summernote.min.js'/>"></script>
<link href="<c:url value='/webjars/summernote/0.8.12/dist/summernote.css'/>" rel="stylesheet" />

<link rel="stylesheet" media="all" href='<c:url value="/roller-ui/styles/roller.css"/>' />

<script src="<c:url value="/theme/scripts/roller.js"/>"></script>

