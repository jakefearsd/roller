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
/* Created on Nov 11, 2003 */
package org.apache.roller.weblogger.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.util.DateUtil;

/**
 * Loads MT-Bannedwordslist style bannedwordslist from disk and allows callers to test
 * strings against the bannedwordslist and (optionally) addition bannedwordslists.
 * <br />
 * First looks for bannedwordslist.txt in uploads directory, than in classpath
 * as /bannedwordslist.txt.
 * <br />
 * Bannedwordslist is formatted one entry per line.
 * Any line that begins with # is considered to be a comment. 
 * Any line that begins with ( is considered to be a regex expression. 
 * <br />
 * For more information on the (discontinued) MT-Bannedwordslist service:
 * http://www.jayallen.org/projects/mt-bannedwordslist.
 *
 * @author Lance Lavandowska
 * @author Allen Gilliland
 */
public final class Bannedwordslist {
    
    private static final Log mLogger = LogFactory.getLog(Bannedwordslist.class);
    
    private static final Bannedwordslist bannedwordslist;
    private static final String BANNEDWORDSLIST_FILE = "bannedwordslist.txt";
    private static final String LAST_UPDATE_STR = "Last update:";

    private Date lastModified = null;
    private final List<String> bannedwordslistStr = new ArrayList<>();
    private final List<Pattern> bannedwordslistRegex = new ArrayList<>();
    
    // setup our singleton at class loading time
    static {
        mLogger.info("Initializing MT Bannedwordslist");
        bannedwordslist = new Bannedwordslist();
        bannedwordslist.loadBannedwordslistFromFile(null);
    }
    
    /**
     * Hidden constructor; package-private rather than private so that tests
     * can build an isolated list instead of loading rules into the singleton,
     * whose rules accumulate for the life of the JVM.
     */
    Bannedwordslist() {
    }
      
    /** Singleton factory method. */
    public static Bannedwordslist getBannedwordslist() {
        return bannedwordslist;
    }
    
    /**
     * Load the MT bannedwordslist from the file system.
     * We look for a previously downloaded version of the bannedwordslist first and
     * if it's not found then we load the default bannedwordslist packed with Roller.
     * Only public for purposes of unit testing.
     */
    public void loadBannedwordslistFromFile(String bannedwordslistFilePath) {
        
        InputStream txtStream;
        try {
            String path = bannedwordslistFilePath;
            if (path == null) {
                String uploadDir = WebloggerConfig.getProperty("uploads.dir");
                path = uploadDir + File.separator + BANNEDWORDSLIST_FILE;
            }
            File bannedwordslistFile = new File(path);
            
            // check our lastModified date to see if we need to re-read the file
            if (this.lastModified != null &&
                    this.lastModified.getTime() >= bannedwordslistFile.lastModified()) {
                mLogger.debug("Bannedwordslist is current, no need to load again");
                return;
            } else {
                this.lastModified = new Date(bannedwordslistFile.lastModified());
            }           
            txtStream = new FileInputStream(bannedwordslistFile);
            mLogger.info("Loading bannedwordslist from "+path);
            
        } catch (Exception e) {
            // Roller keeps a copy in the webapp just in case
            txtStream = getClass().getResourceAsStream("/bannedwordslist.txt");
            mLogger.warn(
                "Couldn't find downloaded bannedwordslist, loaded bannedwordslist.txt from classpath instead");
        }
        
        if (txtStream != null) {
            readFromStream(txtStream, false);
        } else {
            mLogger.error("Couldn't load a bannedwordslist file from anywhere, "
                        + "this means bannedwordslist checking is disabled for now.");
        }
        mLogger.info("Number of bannedwordslist string rules: "+bannedwordslistStr.size());
        mLogger.info("Number of bannedwordslist regex rules: "+bannedwordslistRegex.size());
    }
       
    /**
     * Read in the InputStream for rules.
     * @param txtStream stream to read from
     */
    private String readFromStream(InputStream txtStream, boolean saveStream) {
        String line;
        StringBuilder buf = new StringBuilder();
        BufferedReader in = null;
        try {
            in = new BufferedReader(
                    new InputStreamReader( txtStream, StandardCharsets.UTF_8) );
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#")) {
                    readComment(line);
                } else {
                    readRule(line);
                }
                
                if (saveStream) {
                    buf.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            mLogger.error(e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e1) {
                mLogger.error(e1);
            }
        }
        return buf.toString();
    }
    
    private void readRule(String str) {
        // check for bad condition
        if (StringUtils.isEmpty(str)) {
            return;
        }
        
        String rule = str.trim();

        // line has a comment?
        if (str.indexOf('#') > 0) {
            int commentLoc = str.indexOf('#');
            // strip comment; cut at the '#' itself, not one character before
            // it, or a rule written as "word# note" loses its last letter
            rule = str.substring(0, commentLoc).trim();
        }

        // regex rule?
        if (rule.indexOf( '(' ) > -1) {
            // pre-compile patterns since they will be frequently used
            bannedwordslistRegex.add(Pattern.compile(rule));
        } else if (StringUtils.isNotEmpty(rule)) {
            bannedwordslistStr.add(rule);
        }
    }
        
    /** Read comment and try to parse out "Last update" value */
    private void readComment(String str) {
        int lastUpdatePos = str.indexOf(LAST_UPDATE_STR);
        if (lastUpdatePos > -1) {
            str = str.substring(lastUpdatePos + LAST_UPDATE_STR.length());
            str = str.trim();
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                lastModified = DateUtil.parse(str, sdf);
            } catch (ParseException e) {
                mLogger.debug("ParseException reading " + str);
            }
        }
    }
       
    /** 
     * Does the String argument match any of the rules in the built-in bannedwordslist?
     */
    public boolean isBannedwordslisted(String str) {
        return isBannedwordslisted(str, null, null);
    }
    
    /** 
     * Does the String argument match any of the rules in the built-in bannedwordslist
     * plus additional bannedwordslists provided by caller?
     * @param str             String to be checked against bannedwordslist
     * @param moreStringRules Additional string rules to consider
     * @param moreRegexRules  Additional regex rules to consider 
     */
    public boolean isBannedwordslisted(
         String str, List<String> moreStringRules, List<Pattern> moreRegexRules) {
        if (str == null || StringUtils.isEmpty(str)) {
            return false;
        }

        // First iterate over bannedwordslist, doing indexOf.
        // Then iterate over bannedwordslistRegex and test.
        // As soon as there is a hit in either case return true
        
        // test plain String.indexOf
        List<String> stringRules = bannedwordslistStr;
        if (moreStringRules != null && !moreStringRules.isEmpty()) {
            stringRules = new ArrayList<>();
            stringRules.addAll(moreStringRules);
            stringRules.addAll(bannedwordslistStr);
        }
        if (testStringRules(str, stringRules)) {
            return true;
        }
        
        // test regex bannedwordslisted
        List<Pattern> regexRules = bannedwordslistRegex;
        if (moreRegexRules != null && !moreRegexRules.isEmpty()) {
            regexRules = new ArrayList<>();
            regexRules.addAll(moreRegexRules);
            regexRules.addAll(bannedwordslistRegex);
        }
        return testRegExRules(str, regexRules);
    }      

    /** 
     * Test string only against rules provided by caller, NOT against built-in bannedwordslist.
     * @param str             String to be checked against rules
     * @param stringRules String rules to consider
     * @param regexRules  Regex rules to consider
     */
    public static boolean matchesRulesOnly(
        String str, List<String> stringRules, List<Pattern> regexRules) {
        return testStringRules(str, stringRules) || testRegExRules(str, regexRules);
    }
        
    /** Test String against the RegularExpression rules. */
    private static boolean testRegExRules(String str, List<Pattern> regexRules) {
        for (Pattern testPattern : regexRules) {
            // want to see what it is matching on, but only in debug mode
            if (mLogger.isDebugEnabled()) {
                Matcher matcher = testPattern.matcher(str);
                if (matcher.find()) {
                    mLogger.debug(matcher.group()
                            + " matched by " + testPattern.pattern());
                    return true;
                }
            } else {
                if (testPattern.matcher(str).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tests the source text against the String rules. Each String rule is
     * first treated as a word-boundary, case insensitive regular expression.
     * If a PatternSyntaxException is encountered, a simple contains test
     * is performed.
     *
     * @param source The text in which to apply the matching rules.
     * @param rules A list a simple matching rules.
     *
     * @return true if a match was found, otherwise false
     */
    private static boolean testStringRules(String source, List<String> rules) {
        boolean matches = false;
        
        for (String rule : rules) {

            try {
                StringBuilder patternBuilder;
                patternBuilder = new StringBuilder();
                patternBuilder.append("\\b(");
                patternBuilder.append(rule);
                patternBuilder.append(")\\b");

                Pattern pattern;
                pattern = Pattern.compile(patternBuilder.toString(),
                        Pattern.CASE_INSENSITIVE);

                Matcher matcher;
                matcher = pattern.matcher(source);

                matches = matcher.find();
                if (matches) {
                    break;
                }
            }
            catch (PatternSyntaxException e) {
                matches = source.contains(rule);
                if (matches) {
                    break;
                }
            }
            finally {
                if (matches && mLogger.isDebugEnabled()) {
                    // Log the matched rule in debug mode
                    mLogger.debug("matched:" + rule + ":");
                }
            }
        }
        
        return matches;
    }   
    
    /** Utility method to populate lists based a bannedwordslist in string form */
    public static void populateSpamRules(
        String bannedwordslist, List<String> stringRules, List<Pattern> regexRules, String addendum) {
        String weblogWords = bannedwordslist;
        weblogWords = (weblogWords == null) ? "" : weblogWords;
        String siteWords = (addendum != null) ? addendum : "";
        StringTokenizer toker = new StringTokenizer(siteWords + "\n" + weblogWords, "\n");
        while (toker.hasMoreTokens()) {
            String token = toker.nextToken().trim();
            if (token.startsWith("#")) {
                continue;
            }
            if (token.startsWith("(")) {
                regexRules.add(Pattern.compile(token));
            } else {
                stringRules.add(token);
            }
        }        
    }
        
    /** Return pretty list of String and RegEx rules. */
    @Override
    public String toString() {
        String val = "bannedwordslist " + bannedwordslistStr;
        val += "\nRegex bannedwordslist " + bannedwordslistRegex;
        return val;
    }
}
