# Roller Simplification Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove four feature areas from Apache Roller — the legacy API layer (XML-RPC, Atom Publishing, OAuth), public user registration and LDAP auth, overbuilt comment spam infrastructure, and Java-based mobile device detection.

**Architecture:** Each commit is a self-contained removal of one feature area. The pattern for each is: delete Java source files → remove references in surviving files → update config/XML → verify build → run tests → commit. No new code is written; this is pure subtraction.

**Tech Stack:** Java 17, Spring MVC, Guice DI, JPA/EclipseLink, Maven, Derby/PostgreSQL, Velocity templates

**Spec:** `docs/superpowers/specs/2026-03-29-roller-simplification-phase2-design.md`

---

## File Map

### Commit 1 — Remove API Layer (XML-RPC, Atom Publishing, OAuth)

**Delete entirely:**
- `app/src/main/java/org/apache/roller/weblogger/webservices/xmlrpc/BaseAPIHandler.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/xmlrpc/BloggerAPIHandler.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/xmlrpc/MetaWeblogAPIHandler.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/xmlrpc/package-info.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/RollerAtomHandlerFactory.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/RollerAtomHandler.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/RollerAtomService.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/EntryCollection.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/MediaCollection.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/atomprotocol/package-info.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/oauth/RequestTokenServlet.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/oauth/AuthorizationServlet.java`
- `app/src/main/java/org/apache/roller/weblogger/webservices/oauth/AccessTokenServlet.java`
- `app/src/main/java/org/apache/roller/weblogger/pojos/OAuthConsumerRecord.java`
- `app/src/main/java/org/apache/roller/weblogger/pojos/OAuthAccessorRecord.java`
- `app/src/main/java/org/apache/roller/weblogger/business/OAuthManager.java`
- `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAOAuthManagerImpl.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/OAuthAuthorizeController.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/OAuthKeysController.java`
- `app/src/main/webapp/WEB-INF/jsps/core/OAuthAuthorize.jsp`
- `app/src/main/webapp/WEB-INF/jsps/core/OAuthKeys.jsp`
- `app/src/main/resources/org/apache/roller/weblogger/pojos/OAuthConsumerRecord.orm.xml`
- `app/src/main/resources/org/apache/roller/weblogger/pojos/OAuthAccessorRecord.orm.xml`
- `app/src/test/java/org/apache/roller/weblogger/business/jpa/JPAOAuthManagerTest.java`

**Modify:**
- `app/src/main/webapp/WEB-INF/web.xml` — remove XML-RPC, Atom, and OAuth servlet/filter definitions
- `app/src/main/resources/META-INF/persistence.xml` (or equivalent) — remove OAuth ORM file references
- `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml` — remove webservices and oauth property definitions
- `app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java` — remove `getOAuthManager()` method
- `app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java` — remove `getOAuthManager()` implementation and field
- `app/src/main/resources/org/apache/roller/weblogger/ui/menu/admin-menu.xml` (if OAuth links present) — remove OAuth menu items
- `app/pom.xml` — remove xmlrpc-common, xmlrpc-client, xmlrpc-server, rome-propono, net.oauth dependencies
- `app/src/main/resources/sql/createdb.vm` — remove `roller_oauthconsumer` and `roller_oauthaccessor` CREATE TABLE blocks
- `app/src/main/resources/sql/droptables.sql` — remove the two OAuth DROP TABLE statements

**Create:**
- `app/src/main/resources/sql/migration/520-to-600-migration.sql` — drops OAuth tables for existing installations

### Commit 2 — Remove Public Registration and LDAP

**Delete entirely:**
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/RegisterController.java`
- `app/src/main/webapp/WEB-INF/jsps/core/Register.jsp`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/LdapCommentAuthenticator.java`

**Modify:**
- `app/src/main/webapp/WEB-INF/security.xml` — remove `/roller-ui/register.rol` permit, remove LDAP `AuthenticationProvider` and bean definitions
- `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml` — remove `users.registration.*` and `user.account.email.activation` property definitions
- `app/src/main/java/org/apache/roller/weblogger/util/MailUtil.java` — remove `sendUserActivationEmail` method
- `app/src/main/java/org/apache/roller/weblogger/pojos/User.java` — remove `activationCode` field and its getter/setter
- `app/src/main/java/org/apache/roller/weblogger/business/UserManager.java` — remove activation-related method signatures
- `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java` — remove activation-related method implementations

### Commit 3 — Simplify Comment System

**Delete entirely:**
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/AkismetCommentValidator.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/BannedwordslistCommentValidator.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/ExcessLinksCommentValidator.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/ExcessSizeCommentValidator.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/MathCommentAuthenticator.java`
- `app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/SmileysPlugin.java`
- `app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/ObfuscateEmailPlugin.java`
- `app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/EncodePreTagsPlugin.java`
- `app/src/test/java/org/apache/roller/weblogger/business/plugins/entry/EncodePreTagsPluginTest.java`

**Modify:**
- `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties` — remove akismet.*, banlist.*, comment.maxLinks, comment.maxSize properties; remove SmileysPlugin, ObfuscateEmailPlugin, EncodePreTagsPlugin from plugins.page list; remove MathCommentAuthenticator from authenticator config
- `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml` — remove validator and authenticator property definitions

### Commit 4 — Remove Mobile Device Detection

**Delete entirely:**
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/Device.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/LiteDevice.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/DeviceType.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/DeviceResolver.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/LiteDeviceResolver.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/DeviceUtils.java`
- `app/src/main/java/org/apache/roller/weblogger/ui/rendering/filters/DeviceResolverRequestFilter.java`
- `app/src/main/webapp/themes/basicmobile/` (entire directory)

**Modify:**
- `app/src/main/webapp/WEB-INF/web.xml` — remove `DeviceResolverRequestFilter` filter and filter-mapping
- Any rendering model classes that inject device type into the Velocity context — remove device attribute

---

## Task 1: Remove XML-RPC, Atom Publishing, and OAuth

### Step 1.1: Delete all webservices Java source and OAuth entities/manager

- [ ] Run:
```bash
git rm -r app/src/main/java/org/apache/roller/weblogger/webservices/
git rm app/src/main/java/org/apache/roller/weblogger/pojos/OAuthConsumerRecord.java
git rm app/src/main/java/org/apache/roller/weblogger/pojos/OAuthAccessorRecord.java
git rm app/src/main/java/org/apache/roller/weblogger/business/OAuthManager.java
git rm app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAOAuthManagerImpl.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/OAuthAuthorizeController.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/OAuthKeysController.java
git rm app/src/main/webapp/WEB-INF/jsps/core/OAuthAuthorize.jsp
git rm app/src/main/webapp/WEB-INF/jsps/core/OAuthKeys.jsp
git rm app/src/main/resources/org/apache/roller/weblogger/pojos/OAuthConsumerRecord.orm.xml
git rm app/src/main/resources/org/apache/roller/weblogger/pojos/OAuthAccessorRecord.orm.xml
git rm app/src/test/java/org/apache/roller/weblogger/business/jpa/JPAOAuthManagerTest.java
```

### Step 1.2: Remove OAuth from the Weblogger facade

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/business/Weblogger.java` and find the `getOAuthManager()` method declaration. Remove it and any associated import for `OAuthManager`.

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/business/WebloggerImpl.java` (or `DefaultWeblogger.java` — whichever is the concrete implementation). Remove:
  - The `OAuthManager` field declaration
  - The `getOAuthManager()` method body
  - Any Guice `@Inject` for `OAuthManager`
  - The import for `OAuthManager` and `JPAOAuthManagerImpl`

- [ ] Read the Guice module that binds `OAuthManager` (search for `JPAOAuthManagerImpl` in `*Module.java` files under `app/src/main/java`). Remove the `bind(OAuthManager.class).to(JPAOAuthManagerImpl.class)` line.

### Step 1.3: Remove OAuth ORM registrations from persistence config

- [ ] Find the JPA persistence configuration file. Run:
```bash
find app/src/main/resources/META-INF -name "*.xml" | head -5
```
Open it and remove the two `<mapping-file>` entries for `OAuthConsumerRecord.orm.xml` and `OAuthAccessorRecord.orm.xml`.

### Step 1.4: Remove webservices servlets and OAuth filters from web.xml

- [ ] Read `app/src/main/webapp/WEB-INF/web.xml`. Find and remove all `<servlet>` and `<servlet-mapping>` blocks for:
  - `RollerXmlRpcServlet` (or equivalent name for XML-RPC)
  - Atom Publishing servlet (look for `atom` or `AtomServer` in the name)
  - `RequestTokenServlet`, `AuthorizationServlet`, `AccessTokenServlet` (OAuth)

  Also remove any `<filter>` and `<filter-mapping>` blocks for OAuth.

### Step 1.5: Remove webservices config properties from runtimeConfigDefs.xml

- [ ] Read `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml`. Remove the entire `<display-group>` or individual `<property>` entries for:
  - `webservices.enableXmlRpc`
  - `webservices.enableAtomPub`
  - `webservices.atomPubAuth`
  - All `oauth.*` properties

### Step 1.6: Remove Maven dependencies

- [ ] Read `app/pom.xml`. Remove the `<dependency>` blocks for:
  - `xmlrpc-common`, `xmlrpc-client`, `xmlrpc-server` (Apache XML-RPC — search for `apache.xmlrpc` or `xmlrpc` in groupId/artifactId)
  - `rome-propono` (Atom Publishing Protocol client — distinct from `rome` which stays for feed generation)
  - Any `net.oauth` dependency (OAuth 1.0a library)

### Step 1.7: Update createdb.vm and droptables.sql

- [ ] Read `app/src/main/resources/sql/createdb.vm`. Remove the two `CREATE TABLE roller_oauthconsumer` and `CREATE TABLE roller_oauthaccessor` blocks, and the `CREATE INDEX oc_username_idx` line.

- [ ] Read `app/src/main/resources/sql/droptables.sql`. Remove:
```sql
drop table roller_oauthconsumer;
drop table roller_oauthaccessor;
```

### Step 1.8: Write the migration script

- [ ] Create `app/src/main/resources/sql/migration/520-to-600-migration.sql` with:
```sql
-- Roller 5.2.x to 6.0.0 migration
-- Remove OAuth tables (OAuth 1.0a API removed)

DROP TABLE IF EXISTS roller_oauthaccessor;
DROP TABLE IF EXISTS roller_oauthconsumer;
```

Note: Use `DROP TABLE IF EXISTS` for safety. The migration runner will apply this on first startup after upgrade.

### Step 1.9: Verify build compiles

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn -DskipTests=true install 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`. If there are compilation errors, they will point to remaining references to deleted classes — fix each one.

### Step 1.10: Run tests

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn test 2>&1 | tail -30
```
Expected: All remaining tests pass. Any failures in OAuth-related tests are expected — check that those test classes were deleted in Step 1.1.

### Step 1.11: Commit

- [ ] Run:
```bash
git add -A
git commit -m "Remove XML-RPC, Atom Publishing Protocol, and OAuth 1.0a API layer

All content management goes through the Roller web UI.
Legacy API protocols removed along with OAuth tables (roller_oauthconsumer,
roller_oauthaccessor). Migration script added at sql/migration/520-to-600-migration.sql."
```

---

## Task 2: Remove Public Registration and LDAP

### Step 2.1: Delete registration controller, JSP, and LDAP authenticator

- [ ] Run:
```bash
git rm app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/RegisterController.java
git rm app/src/main/webapp/WEB-INF/jsps/core/Register.jsp
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/LdapCommentAuthenticator.java
```

### Step 2.2: Remove registration permit and LDAP from security.xml

- [ ] Read `app/src/main/webapp/WEB-INF/security.xml`.

  Remove the `<intercept-url>` line that permits unauthenticated access to `register.rol`, e.g.:
  ```xml
  <intercept-url pattern="/roller-ui/register.rol*" access="permitAll"/>
  ```

  Remove the LDAP `AuthenticationProvider` reference from the `<authentication-manager>` block, e.g.:
  ```xml
  <authentication-provider ref="ldapAuthProvider"/>
  ```

  Remove the LDAP bean definitions (the commented-out block or any active beans for `ldapAuthProvider`, `ldapUserSearch`, `ldapAuthoritiesPopulator`, `contextSource`).

### Step 2.3: Remove activation code from the User pojo

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/pojos/User.java`. Find and remove:
  - The `activationCode` field (and its `@Column` annotation if present)
  - The `getActivationCode()` getter
  - The `setActivationCode(String)` setter

### Step 2.4: Remove activation email from MailUtil

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/util/MailUtil.java`. Find and remove the `sendUserActivationEmail(...)` method entirely, along with any imports it uniquely required.

### Step 2.5: Remove activation methods from UserManager interface and implementation

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/business/UserManager.java`. Find and remove any method signatures related to activation (e.g., `activateUser(String activationCode)`, `getUserByActivationCode(String)`).

- [ ] Read `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java`. Remove the corresponding method implementations. Also remove any code in `addUser()` or similar that generates or stores an activation code.

### Step 2.6: Remove registration config properties

- [ ] Read `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml`. Remove `<property>` entries for:
  - `users.registration.enabled`
  - `users.registration.url`
  - `user.account.email.activation`

- [ ] Read `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`. Remove the default values for those same three properties.

### Step 2.7: Verify build compiles

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn -DskipTests=true install 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`. Fix any remaining references to deleted classes.

### Step 2.8: Run tests

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn test 2>&1 | tail -30
```
Expected: All remaining tests pass.

### Step 2.9: Commit

- [ ] Run:
```bash
git add -A
git commit -m "Remove public user registration and LDAP authentication

All accounts are administrator-created. Self-registration, email activation
flow, and LDAP auth provider removed. LdapCommentAuthenticator also removed."
```

---

## Task 3: Simplify Comment System

### Step 3.1: Delete spam validators, math authenticator, and removed entry plugins

- [ ] Run:
```bash
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/AkismetCommentValidator.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/BannedwordslistCommentValidator.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/ExcessLinksCommentValidator.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/ExcessSizeCommentValidator.java
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/plugins/comments/MathCommentAuthenticator.java
git rm app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/SmileysPlugin.java
git rm app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/ObfuscateEmailPlugin.java
git rm app/src/main/java/org/apache/roller/weblogger/business/plugins/entry/EncodePreTagsPlugin.java
git rm app/src/test/java/org/apache/roller/weblogger/business/plugins/entry/EncodePreTagsPluginTest.java
```

### Step 3.2: Update roller.properties to remove deleted plugin/validator references

- [ ] Read `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`.

  Find the `plugins.page` property (the comma-separated list of entry plugin class names). Remove these three from the list:
  - `org.apache.roller.weblogger.business.plugins.entry.SmileysPlugin`
  - `org.apache.roller.weblogger.business.plugins.entry.ObfuscateEmailPlugin`
  - `org.apache.roller.weblogger.business.plugins.entry.EncodePreTagsPlugin`

  Find the comment validator list property (search for `AkismetCommentValidator` to locate it). Remove all four deleted validator class names from it.

  Find the comment authenticator property (search for `MathCommentAuthenticator`). Remove it, leaving only `DefaultCommentAuthenticator`.

  Remove standalone properties for: `akismet.apiKey`, all `banlist.*` properties, `comment.maxLinks`, `comment.maxSize`.

### Step 3.3: Update runtimeConfigDefs.xml

- [ ] Read `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml`. Remove `<property>` definitions for `akismet.apiKey`, the banlist properties, `comment.maxLinks`, `comment.maxSize`, and any authenticator-selection property.

### Step 3.4: Remove Akismet Maven dependency (if present)

- [ ] Read `app/pom.xml`. Search for `akismet`. If a dependency exists, remove it.

### Step 3.5: Check plugin wiring for removed validators

- [ ] Run:
```bash
grep -r "AkismetCommentValidator\|BannedwordslistCommentValidator\|ExcessLinksCommentValidator\|ExcessSizeCommentValidator\|MathCommentAuthenticator\|SmileysPlugin\|ObfuscateEmailPlugin\|EncodePreTagsPlugin" app/src/main/java/ --include="*.java"
```
Expected: No output. If anything comes back, read the file and remove the reference.

### Step 3.6: Verify build compiles

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn -DskipTests=true install 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`.

### Step 3.7: Run tests

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn test 2>&1 | tail -30
```
Expected: All remaining tests pass. Any test that directly instantiated a deleted class will have been deleted in Step 3.1 — confirm no new failures.

### Step 3.8: Commit

- [ ] Run:
```bash
git add -A
git commit -m "Simplify comment system — remove spam validators and extra plugins

Removed: AkismetCommentValidator, BannedwordslistCommentValidator,
ExcessLinksCommentValidator, ExcessSizeCommentValidator, MathCommentAuthenticator.
Kept: DefaultCommentAuthenticator, basic moderation toggle, HTMLSubsetPlugin,
AutoformatPlugin, LinkMarkupPlugin, ConvertLineBreaksPlugin.
Also removed unused entry plugins: SmileysPlugin, ObfuscateEmailPlugin, EncodePreTagsPlugin."
```

---

## Task 4: Remove Mobile Device Detection

### Step 4.1: Delete all mobile device detection classes and basicmobile theme

- [ ] Run:
```bash
git rm -r app/src/main/java/org/apache/roller/weblogger/ui/rendering/util/mobile/
git rm app/src/main/java/org/apache/roller/weblogger/ui/rendering/filters/DeviceResolverRequestFilter.java
git rm -r app/src/main/webapp/themes/basicmobile/
```

  If there is also a `app/src/main/java/org/apache/roller/weblogger/ui/rendering/mobile/` directory, remove it too:
```bash
git rm -r app/src/main/java/org/apache/roller/weblogger/ui/rendering/mobile/ 2>/dev/null || true
```

### Step 4.2: Remove DeviceResolverRequestFilter from web.xml

- [ ] Read `app/src/main/webapp/WEB-INF/web.xml`. Find and remove the `<filter>` block for `DeviceResolverRequestFilter` and its matching `<filter-mapping>` block.

### Step 4.3: Remove device detection from the rendering pipeline

- [ ] Run:
```bash
grep -r "DeviceResolver\|LiteDeviceResolver\|DeviceType\|DeviceUtils\|DeviceResolverRequestFilter\|device\|mobile" \
  app/src/main/java/org/apache/roller/weblogger/ui/rendering/ \
  --include="*.java" -l
```
For each file returned, read it and remove any references to device type — typically this means removing a `device` attribute being added to the Velocity context, or a conditional branch that chose a mobile theme. The goal is that all requests go through the standard (desktop) rendering path.

### Step 4.4: Check for stray device references

- [ ] Run:
```bash
grep -r "DeviceResolver\|LiteDeviceResolver\|DeviceType\|DeviceUtils\|basicmobile" \
  app/src/main/java/ app/src/main/webapp/ \
  --include="*.java" --include="*.xml" --include="*.jsp" --include="*.vm"
```
Expected: No output. Fix any hits.

### Step 4.5: Verify build compiles

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn -DskipTests=true install 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`.

### Step 4.6: Run full test suite

- [ ] Run:
```bash
cd /home/jakefear/source/roller && mvn test 2>&1 | tail -30
```
Expected: All remaining tests pass.

### Step 4.7: Smoke test the running application

- [ ] Start the dev server:
```bash
cd app && mvn jetty:run 2>&1 &
# Wait for "Started ServerConnector" in output
```

- [ ] Verify these pages load without errors:
  1. `http://localhost:8083/roller/` — front page
  2. `http://localhost:8083/roller/roller-ui/login.rol` — login page
  3. After logging in: `http://localhost:8083/roller/roller-ui/menu.rol` — main menu
  4. `http://localhost:8083/roller/roller-ui/authoring/entries.rol?weblog=<handle>` — entries list
  5. `http://localhost:8083/roller/roller-ui/authoring/mediaFileView.rol?weblog=<handle>` — media files
  6. `http://localhost:8083/roller/roller-ui/admin/globalConfig.rol` — global config
  7. `http://localhost:8083/roller/roller-ui/admin/userAdmin.rol` — user admin
  8. Verify the admin menu has no broken links (no OAuth, no XML-RPC options)
  9. Verify the editor menu has no Register link visible anywhere
  10. Navigate to a weblog's public URL and verify it renders correctly

### Step 4.8: Commit

- [ ] Run:
```bash
git add -A
git commit -m "Remove Java mobile device detection layer

Bootstrap responsive CSS handles all screen sizes. The Java device-detection
filter, DeviceResolver, and associated classes removed. basicmobile theme
removed (it only existed to serve device-detected mobile requests)."
```

---

## Self-Review Notes

- **Spec coverage:** All four sections from the spec are covered. Schema impact (Section 1 migration script) covered in Step 1.8. The `roller_properties` data cleanup SQL from the spec is informational — no task added since it's non-breaking orphaned data, not a required change.
- **Placeholder scan:** All steps contain either `git rm` commands, grep commands, "read the file and remove X" instructions with the exact thing to remove, or build/commit commands. No TBDs.
- **Type consistency:** No new types introduced — this is deletion-only work.
- **Note on Step 2.5:** The exact method signatures for activation in `UserManager` aren't verified ahead of time — the implementer should search for `activation` in both the interface and implementation and remove all hits.
