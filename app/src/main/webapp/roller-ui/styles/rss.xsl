<?xml version="1.0" encoding="UTF-8"?>
<!--
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
  
-->
<xsl:stylesheet 
   xmlns:xsl="http://www.w3.org/1999/XSL/Transform" 
   xmlns:dc="http://purl.org/dc/elements/1.1/" version="1.0">
<xsl:output method="xml"  />
<xsl:template match="/">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<title><xsl:value-of select="rss/channel/title"/></title>
<style>
/*
 * "Quiet Instrument" tokens -- docs/design/design-system.md. A browser
 * pointed at a feed renders this stylesheet, so it is a public surface and
 * carries the same palette as the themes and the admin UI. XSL has no
 * external stylesheet to link (the transform's output never reaches a page
 * that could have loaded one), so the tokens are inlined here; every hex
 * below is one of the spec's values.
 *
 * Fonts are declared by family only. A relative @font-face url() in this
 * block would resolve against the FEED document's URL, not this
 * stylesheet's, so the self-hosted Plex webjar is unreachable from here --
 * system-ui is the intended fallback, not an oversight.
 *
 * Rules for markup this transform does not emit (.bannerBox and its missing
 * two-banner.gif, the search sidebar, table.rollertable, a.entryTitle) were
 * dropped rather than re-tinted; they carried half the off-spec palette and
 * styled nothing.
 */
:root {
    --paper: #F7F9F9;
    --surface: #FFFFFF;
    --ink: #17262A;
    --ink-soft: #5A6E72;
    --line: #DCE4E4;
    --accent: #0F6E68;
    --accent-quiet: #E3F0EE;
    --focus: #2AA198;
}
@media (prefers-color-scheme: dark) {
    :root {
        --paper: #131C1C;
        --surface: #1A2626;
        --ink: #DCE7E5;
        --ink-soft: #8FA5A2;
        --line: #2A3838;
        --accent: #4FB3AA;
        --accent-quiet: #1E3230;
        --focus: #2AA198;
    }
}
body {
    background: var(--paper);
    color: var(--ink);
    margin: 0px;
    padding: 0px;
    font-family: "IBM Plex Sans", system-ui, sans-serif;
    font-size: 14.5px;
    font-weight: 450;
    line-height: 1.55;
}
#banner {
    margin: 0px;
    padding: 0px;
}
.bannerStatusBox {
    width: 100%;
    background: var(--accent-quiet);
    color: var(--ink-soft);
    border-bottom: 1px solid var(--line);
}
.bannerStatusBox a, .bannerStatusBox a:link, .bannerStatusBox a:visited {
    color: var(--ink-soft);
    font-weight: 600;
}
.bannerLeft, .bannerRight {
    font-size: 12px;
    font-weight: 600;
    letter-spacing: .08em;
    text-transform: uppercase;
}
.bannerLeft {
    padding: 8px 16px 8px 12px;
}
.bannerRight {
    padding: 8px 12px 8px 16px;
    text-align: right;
}
#centercontent_wrap {
    float: left;
    display: inline;
    width: 100%;
}
#centercontent {
    margin: 24px;
}
#rightcontent_wrap {
    float: right;
    display: inline;
}
#rightcontent {
    margin: 24px;
}
#footer {
    clear: both;
    padding: 16px 0px;
    color: var(--ink-soft);
    font-size: 12px;
    text-align: center;
}
h1 {
    color: var(--ink);
    font-size: 20px;
    font-weight: 600;
}
h2, h3 {
    color: var(--ink);
    font-size: 16px;
    font-weight: 600;
}
p {
    margin: 0px 0px 12px 0px;
}
a, a:link, a:visited {
    color: var(--accent);
}
a:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: 2px;
}
hr {
    border: 0px;
    border-top: 1px solid var(--line);
    margin: 24px 0px;
}
ol {
    padding-left: 24px;
}
ol li {
    margin-bottom: 12px;
    color: var(--ink-soft);
    font-variant-numeric: tabular-nums;
}
ol li h4 {
    margin: 0px 0px 4px 0px;
    font-size: 16px;
    font-weight: 600;
}
</style>
</head>
<body>	

<div id="banner">
    <div class="bannerStatusBox">   
        <table class="bannerStatusBox" cellpadding="0" cellspacing="0">
        <tr>
        <td class="bannerLeft">
            RSS 2.0
        </td>
        <td class="bannerRight">  
            <xsl:value-of select="rss/channel/generator" />
        </td>
        </tr>
        </table>    
    </div>
</div>
    
<div id="wrapper">
    <div id="leftcontent_wrap">
        <div id="leftcontent"> 
        
        </div>
    </div>
    
    <div id="centercontent_wrap">
        <div id="centercontent"> 
            
            <h1>RSS newsfeed</h1>

            <p>This page is an <a href="http://blogs.law.harvard.edu/tech/rss">RSS</a> 
            newsfeed, an XML data representation of the latest entries
            from a Roller weblog. If you have a newsfeed reader or aggregator, you can 
            subscribe to this newsfeed. To subscribe, copy the URL from your browser's 
            address bar above and paste it into your newsfeed reader.</p>
            
            <h1>Latest items in newsfeed [<xsl:value-of select="rss/channel/title"/>]</h1>

            <ol>
                <xsl:for-each select="rss/channel/item">       
                <li>
                    <h4><a><xsl:attribute name="href"><xsl:value-of select="guid"/></xsl:attribute><xsl:value-of select="title"/></a></h4>
                    Published <xsl:value-of select="pubDate"/> by <xsl:value-of select="dc:creator" />
                </li>
                </xsl:for-each>
            </ol>
            <br />      
            <hr />
            <p>To learn more about RSS visit <a href="http://blogs.law.harvard.edu/tech/rss">http://blogs.law.harvard.edu/tech/rss</a></p>

        </div>
    </div>
    
    <div id="rightcontent_wrap">
        <div id="rightcontent"> 
           <br />
        </div>
    </div>
 
</div>

<div id="footer">
   <br />
</div> 
        
<div id="datetagdiv" 
   style="position:absolute;visibility:hidden;background-color:white;layer-background-color:white;">
</div>

</body>
</html>
</xsl:template>
</xsl:stylesheet>
