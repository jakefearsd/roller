<%-- 
This default stuff goes in the HTML head element of each page
You can override it with your own file via WEB-INF/tiles-def.xml

The /webjars/ versions below must match the webjar dependency versions in
app/pom.xml. WebjarReferenceTest fails the build if they drift, because a
mismatch 404s the asset on every page that includes this file.
--%>

<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<script src="<c:url value='/webjars/jquery/3.7.1/jquery.min.js'/>"></script>

<script src="<c:url value='/webjars/jquery-ui/1.14.2/jquery-ui.min.js'/>"></script>
<link href="<c:url value='/webjars/jquery-ui/1.14.2/jquery-ui.css'/>" rel="stylesheet" />

<script src="<c:url value='/webjars/jquery-validation/1.21.0/jquery.validate.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap/5.3.8/css/bootstrap.min.css'/>" rel="stylesheet" />
<script src="<c:url value='/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js'/>"></script>

<link href="<c:url value='/webjars/bootstrap-icons/1.13.1/font/bootstrap-icons.min.css'/>" rel="stylesheet" />

<script src="<c:url value='/webjars/clipboard.js/2.0.11/clipboard.min.js'/>"></script>

<%-- EasyMDE's toolbar icons are Font Awesome 4 glyph classes; self-hosted
     here (autoDownloadFontAwesome:false in EntryEditor.jsp/PageEdit.jsp
     skips EasyMDE's own CDN <link>) rather than fetched from a CDN.

     Font Awesome is a SECOND icon webfont on top of bootstrap-icons, and
     nothing in Roller's own markup uses an fa-* class -- it exists purely
     for that toolbar. Only two content tiles instantiate an editor
     (EntryEdit.jsp, which includes EntryEditor.jsp, and PageEdit.jsp), so
     the font loads on those two screens instead of on every admin page.
     Keyed off ${tile_content} -- the content-area JSP path
     RollerViewResolver publishes as a request attribute for the layout --
     rather than off the request URL, because that is the same value the
     layout uses to decide what it is rendering, so the two cannot disagree.
     If a third screen ever grows an EasyMDE instance, add it here; the
     failure mode is visible (blank toolbar buttons), not silent. --%>
<c:if test="${fn:contains(tile_content, 'EntryEdit.jsp') or fn:contains(tile_content, 'PageEdit.jsp')}">
<link href="<c:url value='/webjars/font-awesome/4.7.0/css/font-awesome.min.css'/>" rel="stylesheet" />
</c:if>

<script src="<c:url value='/webjars/easymde/2.21.0/dist/easymde.min.js'/>"></script>
<link href="<c:url value='/webjars/easymde/2.21.0/dist/easymde.min.css'/>" rel="stylesheet" />

<%-- "Quiet Instrument" design tokens (docs/design/design-system.md). Loads
     after Bootstrap so its custom properties are available, and before
     roller.css so roller.css can override on top of it. --%>
<link rel="stylesheet" media="all" href='<c:url value="/roller-ui/styles/roller-tokens.css"/>' />

<link rel="stylesheet" media="all" href='<c:url value="/roller-ui/styles/roller.css"/>' />

<script src="<c:url value="/theme/scripts/roller.js"/>"></script>

