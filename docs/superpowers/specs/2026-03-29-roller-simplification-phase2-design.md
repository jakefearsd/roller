# Roller Simplification — Phase 2 Design

**Date:** 2026-03-29
**Status:** Approved
**Context:** Apache Roller customized for a small business with 10–50 blogs across two use cases: (1) travel and rental property city guides, (2) Maiia's photography showcase and events. Not a public blogging service — a focused internal business tool.

## Goals

Remove features built for a public multi-tenant blogging *service* that have no place in a small trusted-team deployment. Each removal area is a self-contained commit. No feature-flag disabling — dead code gets deleted.

## What Is NOT Changing

- Core blogging: entries, categories, tags, publishing states, scheduled posts
- Admin UI: user management, global config, global comment moderation
- Editor UI: per-weblog config, member management, maintenance
- Group blogging and per-weblog role permissions (post / edit_draft / admin)
- Lucene full-text search
- Media file management (uploads, directories, image handling)
- Theme system: shared themes, custom themes, template editor, stylesheet editor
- Velocity rendering pipeline
- RSS/Atom *feed* generation (Rome library) — public `/feed` URLs stay
- Caching infrastructure
- Email notifications (comment alerts, password reset)
- Hit count tracking
- Web analytics code injection

---

## Commit 1: Remove API Layer (XML-RPC, Atom Publishing, OAuth)

**Rationale:** All content is managed through the Roller web UI. No external clients or scripting needed. These protocols are legacy, add attack surface, and can be reimplemented cleanly as a modern REST API if ever needed.

**Delete:**
- Entire `webservices/` Java package (XML-RPC handlers, Atom Publishing Protocol handlers, OAuth servlets)
- `OAuthConsumerRecord`, `OAuthAccessorRecord` JPA entities and ORM mappings
- `OAuthManager` interface and `JPAOAuthManagerImpl`
- `OAuthAuthorize.jsp`, `OAuthKeys.jsp` and their Spring MVC controllers
- Servlet registrations in `web.xml` for XML-RPC, Atom, and OAuth endpoints
- OAuth DB tables: `roller_oauthconsumer`, `roller_oauthaccessor` (add migration script)
- Config properties: `webservices.enableXmlRpc`, `webservices.enableAtomPub`, all `oauth.*` properties
- Maven dependency: Apache XML-RPC (`xmlrpc`)
- Remove `getOAuthManager()` from `Weblogger` facade interface and `DefaultWeblogger`
- Remove OAuth menu items from admin menu and any OAuth links in JSPs

**Keep:**
- Rome library — used for public RSS/Atom *feed* URLs, not the write API

---

## Commit 2: Remove Public Registration and LDAP

**Rationale:** This is not a hosting service. All accounts are created by an administrator. LDAP is not part of the infrastructure.

**Delete:**
- `RegisterController.java` and `Register.jsp`
- `/roller-ui/register.rol` permit in `security.xml`
- Email activation flow: activation token generation, activation email template, "pending activation" code paths throughout `UserManager`
- Config properties: `users.registration.enabled`, `users.registration.url`, `user.account.email.activation` and all code branches on them
- Commented-out LDAP `AuthenticationProvider` block in `security.xml`
- Any `ldap.*` config properties
- `LdapCommentAuthenticator` plugin

**Keep:**
- `UserEditController` — admin creates all accounts via this
- `user.enabled` flag — admins can still disable accounts manually
- Password reset email flow — useful for a small internal team

---

## Commit 3: Simplify Comment System

**Rationale:** Comments are kept for reader engagement but spam-detection infrastructure is overkill for a small controlled deployment. Basic moderation is sufficient; more can be added if real problems emerge.

### Remove spam validator plugins:
- `AkismetCommentValidator`
- `BannedwordslistCommentValidator`
- `ExcessLinksCommentValidator`
- `ExcessSizeCommentValidator`
- Config: `akismet.apiKey`, all `banlist.*`, `comment.maxLinks`, `comment.maxSize`
- Maven dependency: Akismet client library (if present as an explicit dependency)

### Remove comment authenticator plugins (except default):
- `MathCommentAuthenticator`
- Authenticator-selection config and any admin UI for choosing an authenticator
- Keep: `DefaultCommentAuthenticator` only (anonymous posting, relies on moderation)

### Remove entry content plugins:
- `SmileysPlugin`
- `ObfuscateEmailPlugin`
- `EncodePreTagsPlugin`

### Keep:
- `users.moderation.required` toggle — approve before publish
- `users.comments.enabled` global toggle
- `users.comments.emailnotify` — email team on new comments
- Per-entry comment settings (open/close per entry)
- Comment content plugins: `HTMLSubsetPlugin`, `AutoformatPlugin`, `LinkMarkupPlugin`, `ConvertLineBreaksPlugin`
- Plugin infrastructure (`WeblogEntryPlugin` interface, plugin manager) — useful extension point

---

## Commit 4: Remove Mobile Device Detection

**Rationale:** Bootstrap's responsive CSS handles all screen sizes. The Java device-detection layer adds complexity with no practical benefit in a Bootstrap-based UI.

**Delete:**
- `DeviceResolver`, `LiteDeviceResolver` and all device-type classes
- `RequestMappingFilter` — the filter that dispatches to mobile vs. desktop rendering paths
- Device-related model attributes exposed to Velocity templates
- `basicmobile` shared theme — only existed to serve device-detected mobile requests
- Device-related config properties

**Keep:**
- All Bootstrap CSS — this is what makes pages responsive on small screens
- Standard rendering pipeline — all requests use the single desktop/standard path
- All other shared themes and custom theme support

---

## Schema Impact

### Commit 1 — 2 tables dropped
- `roller_oauthconsumer`
- `roller_oauthaccessor`

Required schema work:
- Add a migration script (e.g. `520-to-600-migration.vm`) with `DROP TABLE` statements for both tables
- Remove the `CREATE TABLE` definitions from `createdb.vm`
- Remove the `DROP TABLE` entries from `droptables.sql`
- Remove the OAuth index (`oc_username_idx`) from `createdb.vm`

### Commits 2, 3, 4 — No schema changes
Registration, LDAP, comment validators, mobile detection, and entry plugins own no DB tables. Their configuration exists as rows in `roller_properties` (the runtime config table). When their property definitions are removed from `runtimeConfigDefs.xml`, those rows become orphaned but harmless. Optionally clean them up with:
```sql
DELETE FROM roller_properties WHERE name IN (
  'users.registration.enabled', 'users.registration.url', 'user.account.email.activation',
  'akismet.apiKey', 'comment.maxLinks', 'comment.maxSize'
  -- plus any banlist.* and ldap.* properties
);
```

---

## Implementation Notes

- Each commit removes: Java source, JSP/view files, Spring/web.xml registrations, Maven dependencies, config properties
- Commit 1 requires the DB migration script described above
- After each commit: `mvn -DskipTests=true install` must succeed; manual smoke test the affected area
- Verification smoke test: create entry, publish, view blog, post comment, moderate comment, upload media, edit template, check admin menu for dead links

## Out of Scope for This Phase

- i18n infrastructure
- Hit counts / statistics
- Caching
- Multiple DB support (Derby dev + PostgreSQL prod)
- Web analytics injection
- Task scheduling
