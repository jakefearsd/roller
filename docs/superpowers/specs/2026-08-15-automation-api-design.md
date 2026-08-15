# Automation API (`/api/v1`) — Design

**Date:** 2026-08-15
**Status:** approved, not yet implemented
**Scope:** an HTTP API and a thin CLI for automation — agentic publishing,
agentic SEO, and command-line administration. **No UI migration.**

## Goal

Give agents and scripts a first-class way to drive Roller: create and revise
entries, upload and describe media, find and fix SEO gaps, and run the
administrative actions that today exist only as buttons on a JSP page.

The UI is deliberately untouched. This wave delivers value without paying for
the React migration assessed on 2026-08-15, and it builds the API layer that
migration would have needed anyway.

## Non-goals

- No React, no SPA, no changes to any JSP.
- No public blog rendering changes. `ui/rendering` and the Velocity themes are
  not in scope in any way.
- No new permission vocabulary. The API rides `GlobalPermission` and
  `WeblogPermission` exactly as the UI does.
- No MCP server. That is a separate project layered on this API, not part of
  this wave.
- No token-management UI. Tokens are minted through the CLI (see Bootstrap).

## Decisions

Six decisions were settled before design, each recorded here with the reason
so a later reader does not relitigate them.

1. **Scoped API tokens**, in a new table, rather than extending
   `UserToken.Purpose` or accepting HTTP Basic everywhere. `UserToken` is
   single-use with a one-hour TTL and an atomic `consume` — a design that is
   deliberately wrong for a long-lived credential, and overloading it would
   fork every one of those properties.
2. **`/api/v1`, documented unstable while Roller is 0.x.** The version segment
   exists from day one so a future break has somewhere to go; the stability
   promise does not. Retrofitting a version segment later means editing every
   client written by then.
3. **API plus a thin CLI in the same wave.** The CLI doubles as the reference
   client and is what makes "command-line administrative actions" real.
4. **Media upload is in.** An agent that can publish but cannot attach an image
   cannot finish a post — featured images, `og:image` and `[gallery]` all
   require media to exist first.
5. **Bootstrap through `roller-api auth login`**, which sends the account
   password exactly once to mint a token. This is how `gh auth login` and
   `docker login` work. It confines password authentication to one endpoint
   instead of making it a general mechanism, and it avoids requiring either a
   UI screen or shell access on the deploy host.
6. **Token scope is a weblog plus a role ceiling.** Fine-grained per-endpoint
   scopes would be a second permission vocabulary to keep in step with
   `WeblogPermission`, which is real maintenance cost for one operator.

## Architecture

New package `org.apache.roller.weblogger.ui.restapi`, a sibling of
`ui.controllers` and `ui.rendering`:

```
ui/restapi/
├── ApiExceptionHandler.java      # @RestControllerAdvice -> problem+json
├── auth/                         # token filter, principal, scope intersection
├── dto/                          # records + hand-written mappers
└── v1/                           # one @RestController per resource
```

Business managers are reused verbatim. Nothing below the controller line
changes except the four consolidations named below and one new manager for
tokens.

### Mounting

`/api/*` joins `SEO_URL_PATTERNS` and `NEWSLETTER_URL_PATTERNS` in the existing
`DispatcherServletRegistrationBean.configure()` hook in
`ServletRegistrationConfig`. `DispatcherServletRegistrationBean` overrides
`addUrlMappings`/`setUrlMappings` to throw `UnsupportedOperationException`, so
the `configure()` hook is the only way in — the same reason the SEO and
newsletter patterns are added there.

**This is a servlet-spec prefix mapping, and the prefix is stripped from the
Spring MVC lookup path.** Controller mappings are therefore written *relative
to* `/api`:

```java
@RequestMapping("/v1/weblogs/{handle}/entries")   // serves /api/v1/weblogs/...
```

`NewsletterController` already lives with this (`@PostMapping("/subscribe")`
serving `/newsletter/subscribe`). It is the first thing that will be got wrong,
so a test pins one resolved path end to end.

### Filters

Every filter in `ServletRegistrationConfig` registers without
`addUrlPatterns()` and so defaults to `/*`. `CharEncodingFilter`,
`SpringFirewallExceptionFilter`, `BootstrapFilter`, `PersistenceSessionFilter`,
`InitFilter` and `RequestMappingFilter` already cover `/api/**`. In particular
`PersistenceSessionFilter` (order 60) already scopes and releases the
`EntityManager` per request — no new lifecycle work.

### Reserved path root

`api` must be added to `rendering.weblogMapper.rollerProtectedUrls`, or a
weblog whose handle is `api` would shadow the entire API through
`RequestMappingFilter` (order 80), which runs before servlet resolution.

**While doing this, fix `newsletter`.** `ServletRegistrationConfig.java:194`
asserts that "the `newsletter` path root is reserved in `rollerProtectedUrls`
so no weblog handle can shadow it". It is not in the list. This is a latent
defect — it requires someone to create a weblog with the handle `newsletter` —
but the comment claims a protection that does not exist. Add both handles and
pin them with a test.

## Consolidations

Four places where the API would otherwise duplicate the UI. Each consolidation
lands **before** its second caller, so nothing is ever duplicated and then
de-duplicated.

### 1. Entry field rules — the highest-risk item in the wave

`EntryBean.copyTo` runs `StringEscapeUtils.escapeHtml4` on the title. It is the
only place raw author input becomes escaped markup for an entry, which is why
`WeblogEntry.getTitle()` returns entity-escaped text and every theme emits
`$entry.title` bare. `PageBean.copyTo` does the **opposite** — it copies the
title through unescaped, which is why every page template must escape at render
time.

`EntryBean.getPubTime(TimeZone)` parses the submitted wall-clock string against
the **weblog's** timezone, throws on a non-blank unparseable value, and treats
blank as "publish now".

Both move to `EntryFieldRules`, called by `EntryBean` and by the API's entry
writer. Re-deriving the escaping rule in a DTO mapper is stored XSS, and
because the page-side rule is inverted, a copy-paste between the two is a live
bug rather than a near miss.

### 2. Ownership checks

`BaseController.lookupEntry`, `lookupCategory`, `lookupTemplate` and
`lookupPage` each ownership-check a client-supplied id against the action
weblog, because the permission interceptor only vouches for the weblog, not for
arbitrary ids. They move to a `WeblogOwnership` helper; `BaseController`
delegates. One IDOR defense, two callers. All four keep their existing
treatment of a blank id as absent rather than as something to look up.

### 3. Weblog resolution in the permission interceptor

`RollerHandlerInterceptor.preHandle` resolves the action weblog from
`request.getParameter("weblog")`. REST carries the handle as a path variable,
so the interceptor gains a fallback: when the parameter is absent, read the
`handle` URI template variable from
`HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE`, which
`RequestMappingHandlerMapping` populates during `getHandler()` and therefore
before any interceptor runs.

This is the most valuable consolidation in the design: **one authorization path
serves both the UI and the API.** The alternative — a parallel authorization
filter for `/api/**` — would mean two implementations of per-weblog permission
checking that must never disagree.

### 4. Maintenance actions

Flush cache, rebuild search index and regenerate renditions are inline in
`MaintenanceController`. They move to a `MaintenanceService`; the controller and
the API both call it.

`MediaFileAddController.save`'s per-file error handling — snapshotting
`RollerMessages.getErrorCount()` around each `createMediaFile` call, because
quota and forbidden-extension refusals are reported without throwing and a
batch is not a transaction — is already correct and is reused as-is by the
media endpoint. No refactor needed, just a second caller.

## Authentication

### Schema (`V026__api_tokens.sql`)

```
roller_api_token
  id             varchar(48)  primary key
  user_id        varchar(48)  not null references roller_user(id)
  label          varchar(255) not null     -- human name, e.g. "seo-agent"
  token_sha256   varchar(64)  not null unique
  scope_weblog   varchar(255)              -- weblog handle, null = all
  scope_role     varchar(16)  not null     -- read | post | admin
  created        timestamp    not null
  last_used_at   timestamp
  expires_at     timestamp                 -- null = never
  revoked_at     timestamp
```

Beside `roller_user_token`, not inside it. Idempotent DDL, per the migration
convention in `bin/db/migrations/README.md`.

### Token format and storage

`rlr_` followed by 32 random bytes, base64url-encoded, from a
`SecureRandom`. Shown exactly once, at creation, and never retrievable
afterwards.

Stored as a SHA-256 digest only — correct here for the same reason `UserToken`
uses it: the secret is high-entropy random, so there is nothing to brute-force,
and authentication must be a single indexed lookup on every request. A slow KDF
would be wrong on both counts. A database read must never yield a working
credential.

`last_used_at` is written coarsely — only when the stored value is more than an
hour old — so an API call is not also a database write.

### Effective permission

```
effective = intersect(token scope, the user's real permissions)
```

The token is a **ceiling, never a grant**. `GlobalPermission` and
`WeblogPermission` checks run unchanged through `RollerHandlerInterceptor`, so:

- a token cannot exceed what its owner may do;
- revoking the user's weblog permission immediately narrows every token;
- disabling the account kills every token immediately, with no revocation step.

Role ceilings map onto existing actions: `read` grants nothing beyond GET;
`post` maps to `WeblogPermission.POST`; `admin` maps to
`WeblogPermission.ADMIN`, or to `GlobalPermission.ADMIN` when `scope_weblog` is
null and the endpoint is under `/v1/admin`.

### Bootstrap

`POST /api/v1/tokens` is the only endpoint that accepts HTTP Basic, and only to
mint. `roller-api auth login` prompts for the password, sends it once over
HTTPS, stores only the returned token in `~/.roller/credentials` (mode 0600),
and never writes the password anywhere. Every other endpoint requires
`Authorization: Bearer rlr_…`.

A Bearer-authenticated caller may **not** mint a token. Token-mints-token is a
privilege-escalation path that turns any leaked token into a permanent one.

A Bearer caller **may** list and revoke its owner's tokens. Listing exposes no
secret, and revocation only ever removes access — a leaked token that can
revoke itself is strictly better than one that cannot. Only minting is
Basic-only.

### Spring Security wiring

A **second `SecurityFilterChain` bean**, `@Order(1)` with
`securityMatcher("/api/**")`, rather than folding API rules into the existing
chain. The existing chain gains `@Order(2)`; because it declares no
`securityMatcher` it matches everything and must be ordered last.

This is deliberately not "add `/api/**` to `csrf.ignoringRequestMatchers`". That
list currently holds exactly one narrow entry (`isPublicAudiencePost`) and its
narrowness is the point; a separate chain disables CSRF *only* within a matcher
that cannot reach a cookie-authenticated request at all.

The API chain:

- `csrf(AbstractHttpConfigurer::disable)` — correct for a Bearer-authed,
  cookie-free surface.
- `sessionManagement(STATELESS)` — an API call must never mint a session cookie.
- `ApiTokenAuthFilter` (a `OncePerRequestFilter`) before
  `UsernamePasswordAuthenticationFilter`.
- `httpBasic` enabled, reachable only by `POST /api/v1/tokens`; every other path
  under `/api/**` requires an authenticated Bearer principal.

`ApiTokenAuthFilter` sets the `Authentication` **principal to the user name as a
plain `String`**. `RollerHandlerInterceptor.resolveAuthenticatedUser` already
handles a `String` principal (it has a branch for exactly this, excluding
`anonymousUser`), so identity resolution needs no change at all — only the
weblog-resolution fallback in Consolidation 3 does.

## Surface

All paths below are absolute as a client sees them. Controller mappings omit
the `/api` prefix, per Mounting above.

### Identity

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/tokens` | Basic auth only. Body: `label`, optional `weblog`, `role`, `expiresAt`. Returns the raw token once. |
| GET | `/api/v1/tokens` | Caller's own tokens. Metadata only — never the secret. |
| DELETE | `/api/v1/tokens/{id}` | Revoke. Sets `revoked_at`; the row is kept for audit. |
| GET | `/api/v1/me` | Identity, effective permissions, and the calling token's scope, so a client can self-check before acting. |

### Weblogs

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/weblogs` | Those the caller can see. |
| GET | `/api/v1/weblogs/{handle}` | |
| PATCH | `/api/v1/weblogs/{handle}` | Settings. Requires `admin` on the weblog. |

### Entries

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/weblogs/{handle}/entries` | Filters map 1:1 onto `WeblogEntrySearchCriteria`: `status`, `category`, `tags`, `startDate`, `endDate`, `text`, `locale`, `sortBy`, `sortOrder`, `offset`, `limit`. |
| POST | `…/entries` | Create. `status` one of `DRAFT`, `PUBLISHED`, `PENDING`, `SCHEDULED`. |
| GET | `…/entries/{id}` | |
| PATCH | `…/entries/{id}` | Partial update, SEO fields included. |
| DELETE | `…/entries/{id}` | **Trash** (soft delete), matching the UI. |
| POST | `…/entries/{id}/restore` | Restores to `DRAFT`, never to `PUBLISHED`. |
| POST | `…/entries/{id}/delete-forever` | Permanent. Separate verb so it cannot be reached by accident. |
| POST | `…/entries/{id}/preview` | Server-rendered Markdown with shortcodes expanded. |

`TRASHED` is never accepted as a writable status — trashing and restoring go
through the two dedicated endpoints, which are the paths that also maintain the
search index and the `weblog.lastModified` bump.

Listing follows `WeblogEntrySearchCriteria.includeTrashed`, which defaults to
false. The trash is reachable only through `?status=TRASHED`, handled
explicitly.

`POST …/preview` reuses `EntryEditController`'s existing scratch-entry logic:
the preview renders against an unsaved entry owned by the action weblog, so
shortcodes resolve that weblog's media and nothing is persisted. This endpoint
matters more than its size suggests — only the server can expand shortcodes, so
it is the only way an agent can see what it is about to publish.

### Categories and tags

Full CRUD on `…/categories`, plus `GET …/tags`. Category delete takes an
optional `moveTo` target, which is ownership-checked exactly as
`targetCategoryId` is in the UI — a foreign move target would re-file this
weblog's entries into another weblog.

### Media

| Method | Path | Notes |
|---|---|---|
| GET | `…/media` | Filter by directory. |
| POST | `…/media` | Multipart, multiple files. Responds **207** with a per-file result: `created`, `quota_exceeded`, or `forbidden_extension`. |
| GET/PATCH/DELETE | `…/media/{id}` | PATCH covers `altText`, focal point, directory. |
| GET/POST | `…/media/directories` | List and create. |

A batch is not a transaction: partial success is the normal case and the
response says exactly what landed and what did not.

**Private directories are not bypassed.** A private directory is a visibility
flag with no bypass of any kind: it 404s on the public media path for everyone
except a signed-in editor of the owning weblog. The API changes nothing about
that. A caller holding a weblog-scoped token *is* an authorized editor of that
weblog, so listing a private directory through the API is the same access an
editor already has in the UI — not a new hole, and not a tokened public hole of
the kind the removed share-link feature once punched.

### Audit

| Method | Path | Reports |
|---|---|---|
| GET | `…/audit/seo` | Entries with no `searchDescription`, no `metaTitle`, no featured image, or `noindex` set. Counts plus the entries themselves. |
| GET | `…/audit/media` | Media with blank `altText`. |

"Blank" means `isNotBlank`, matching the renderer and the UI's own marker —
**not** EL's `empty`, which would report whitespace-only alt text as present
while every rendered page fell back to the filename. One definition of missing,
used by the API and the UI alike.

### Admin

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/admin/weblogs/{handle}/actions/flush-cache` | Via `MaintenanceService`. |
| POST | `/api/v1/admin/weblogs/{handle}/actions/reindex` | |
| POST | `/api/v1/admin/weblogs/{handle}/actions/regenerate-renditions` | |
| GET/POST | `/api/v1/admin/users` | List, create. |
| PATCH | `/api/v1/admin/users/{userName}` | Enable/disable, roles. |
| GET/PATCH | `/api/v1/admin/config` | Runtime properties, driven by `runtimeConfigDefs.xml`. |

Only runtime-scoped properties are exposed. Startup-scoped settings —
`weblogAdminsUntrusted`, `passwds.encryption.enabled`, `rememberme.enabled`,
`themes.reload.mode`, `users.firstUserAdmin`, `search.enabled` — are
deliberately not settable at runtime and must not become settable through an
API.

### Pages

Full CRUD on `…/pages`, same shape as entries. Slug validation goes through
`ReservedSlugs`, the single source of truth already shared by the page-save
validator and the request parser.

**Cut-first order if the wave needs trimming:** `/v1/admin/config`, then Pages.

## Error contract

RFC 9457 `application/problem+json` everywhere:

```json
{
  "type": "https://roller.invalid/problems/quota-exceeded",
  "title": "Upload quota exceeded",
  "status": 413,
  "detail": "Adding 4.2 MB would exceed this weblog's 100 MB limit.",
  "instance": "/api/v1/weblogs/testing/media"
}
```

One `@RestControllerAdvice` produces every error response, so no controller
invents its own shape. Validation failures carry a machine-readable `errors`
array keyed by field.

Status codes: 400 validation, 401 missing or bad token, 403 authenticated but
not permitted, 404 unknown or not-visible resource, 409 conflict (duplicate
anchor, duplicate slug), 413 quota, 429 throttled, 207 partial batch success.

**404 rather than 403 for a resource the caller may not see**, matching the
private-directory behaviour already in the media path — a 403 confirms
existence.

## Throttling

Per token, through the existing `util/GenericThrottle`, configured under
`api.throttle.*` with the same `enabled`/`threshold`/`interval`/`maxentries`
shape as `contact.throttle.*` and `newsletter.subscribe.throttle.*`. Sizing
stays startup-scoped for the reason recorded in CLAUDE.md: it dimensions a
fixed cache that cannot be resized under live callers.

## CLI

`bin/roller-api` — bash over `curl` and `jq`, baked into the app image and
attached to each release alongside the WAR and the deploy bundle.

Bash rather than a Java picocli fat-jar because it matches this repo's existing
operational tooling (`./roller`, `provision.sh`, `migrate.sh`, `deploy.sh`),
adds no build artifact, and keeps the API itself as the product rather than
creating a second surface to keep in step. Entry bodies are read from a file or
stdin and assembled with `jq`, so no large JSON is ever built by string
concatenation in shell.

```
roller-api auth login --url https://blog.example.com
roller-api auth status

roller-api entries list   --weblog testing --status DRAFT
roller-api entries create --weblog testing --title "..." --text-file post.md --publish
roller-api entries patch  --weblog testing --id <id> --search-description "..."
roller-api entries preview --weblog testing --text-file post.md

roller-api media upload --weblog testing --dir /photos *.jpg
roller-api media patch  --weblog testing --id <id> --alt-text "..."

roller-api audit seo   --weblog testing
roller-api audit media --weblog testing

roller-api admin reindex --weblog testing
roller-api admin flush-cache --weblog testing
```

Configuration precedence: flags, then `ROLLER_API_URL` / `ROLLER_API_TOKEN`
environment variables, then `~/.roller/credentials`. The environment layer is
what makes the CLI usable from CI without an interactive login.

## Testing

- **MockMvc controller tests** in `app/src/test` carry the bulk: routing,
  validation, serialization, permission enforcement, error shapes. The ~90%
  diff-coverage gate (`bin/check-diff-coverage.sh`) applies to all of it.
- **Real-WAR integration tests** in `it-selenium` for the three things MockMvc
  cannot prove. That module already starts the packaged executable WAR through
  antrun (`app-start`/`app-stop`); these tests need no browser:
  1. Bearer authentication end to end through the real filter chain.
  2. Multipart upload through the real rendition/EXIF/BlurHash pipeline.
  3. Prefix-mapping path resolution — that `/api/v1/...` actually reaches a
     controller mapped at `/v1/...`.
- **One configuration test** pinning `api` and `newsletter` in
  `rendering.weblogMapper.rollerProtectedUrls`.
- **`ControllerMetadataTest` already applies** and will fail on any unnamed
  `@RequestParam`/`@PathVariable` — the build does not pass `-parameters`, so a
  bare `@PathVariable String handle` throws at runtime while unit tests calling
  the method directly keep passing. Every parameter in this wave is named
  explicitly.

## Hazards

Repo-specific traps this wave will meet, recorded so they are designed around
rather than discovered:

- **Prefix mapping strips the prefix.** Controller paths omit `/api`.
- **Entry titles are stored escaped; page titles are not.** See Consolidation 1.
  Getting this wrong in a DTO mapper is stored XSS.
- **Trashing must bump `weblog.lastModified`.** `WeblogPageCache` has no
  CacheHandler, so `CacheManager.invalidate` never reaches it and
  `lastModified` is the only thing that expires a rendered page. The existing
  `trashWeblogEntry` already does this; the API must go through it rather than
  setting status directly.
- **`ReIndexEntryOperation` refuses to index a non-`PUBLISHED` entry.** The API
  must route deletes through `trashEntryWithIndex` and
  `deleteEntryForeverWithIndex`, not through the manager directly, or the
  Lucene index orphans.
- **Event double-counting.** `ENTRY_PUBLISHED` is recorded on the transition
  gated by `loadedStatus`, so an unpublish/republish cycle records two events.
  The API inherits this; it is documented behaviour, not a new bug.
- **Editing `runtimeConfigDefs.xml` by hand:** a bare `--` inside an XML comment
  makes the parse fail silently and surfaces as unrelated NPEs.
- **Migration convention:** `V026` must be idempotent and must never be edited
  after it is applied anywhere but local dev.

## Acceptance criteria

Done means all of the following hold, each phrased so a test can check it.

**Mounting and routing**
1. `GET /api/v1/ping` returns 200 from a controller mapped at `/v1/ping`.
2. `api` and `newsletter` both appear in
   `rendering.weblogMapper.rollerProtectedUrls`.

**Authentication**
3. A token issued by `issueToken` authenticates; the raw secret is absent from
   the database, which stores a 64-character digest.
4. An expired token, a revoked token, an unknown token, and a blank token each
   fail to authenticate.
5. `revoke` returns false and changes nothing when the caller does not own the
   named token.
6. An unauthenticated `/api/v1/me` returns 401 and sets no `JSESSIONID`.
7. Every API error response carries `Content-Type: application/problem+json`.
8. A Bearer-authenticated caller receives 403 from `POST /api/v1/tokens`.

**Scope**
9. A token scoped to weblog A receives 404 — not 403 — for any path under
   weblog B.
10. A `read` token receives 403 on any non-GET; a `post` token receives 403 on
    any path under `/api/v1/admin/`.
11. A request carrying no `ApiPrincipal` passes the scope interceptor
    untouched.

**Entries**
12. Creating an entry titled `Cats & Dogs` stores `Cats &amp; Dogs` — escaped
    exactly once.
13. The same `pubTime` wall-clock string produces different instants for two
    weblogs in different timezones.
14. A non-blank unparseable `pubTime` returns 400 and saves nothing.
15. A PATCH body omitting a field leaves that field's stored value unchanged.
16. `TRASHED` is rejected as a writable status and accepted as a filter.
17. `DELETE` on an entry leaves it retrievable via `?status=TRASHED` and absent
    from an unfiltered list.
18. Restore lands on `DRAFT` for an entry that was `PUBLISHED` when trashed.

**Media**
19. A batch containing one good file, one over quota and one forbidden
    extension returns 207 listing all three outcomes.
20. A batch where every file succeeds returns 201.
21. A PATCH omitting `altText` leaves it unchanged; a PATCH sending `""` stores
    the empty string.

**Audit**
22. An entry with whitespace-only `searchDescription` is reported as missing
    it — matching `isNotBlank`, not EL's `empty`.
23. An entry with all SEO fields set and `noindex` false reports no gaps.

**Admin**
24. Each of `weblogAdminsUntrusted`, `passwds.encryption.enabled`,
    `rememberme.enabled`, `themes.reload.mode`, `users.firstUserAdmin` and
    `search.enabled` is rejected by the config endpoint.
25. `POST /api/v1/admin/users` accepts no password field.

**Throttle**
26. A caller exceeding the threshold receives 429; a second caller is
    unaffected.

**CLI**
27. `bin/roller-api` sets `set -euo pipefail`, reads the password with
    `read -rs`, writes the credentials file with `chmod 600`, and stores no
    password in it.

**Whole build**
28. `mvn clean install` passes, including the JaCoCo floors.
29. `mvn verify -Pit` passes, including `ApiIT`.
30. `bin/check-diff-coverage.sh master` reports changed-line coverage at or
    above the ~90% gate.

## Sequencing

Six phases, each shipping working code. Consolidations land with the phase that
first needs them, before the second caller exists.

| Phase | Content |
|---|---|
| 1 | Mounting, reserved path roots (`api` + the `newsletter` fix), problem+json advice, `V026`, `ApiToken` + manager, auth filter, scope intersection, interceptor path-variable fallback, `/v1/tokens` and `/v1/me`. |
| 2 | Entries: list, CRUD, trash/restore/delete-forever, preview. `EntryFieldRules` and `WeblogOwnership` extractions land here. Categories and tags. |
| 3 | Media: list, multipart upload with 207, patch, directories. |
| 4 | Audit endpoints; admin actions via `MaintenanceService`; users; config. Pages. |
| 5 | `bin/roller-api`, shipped in the image and attached to releases. |
| 6 | OpenAPI generation, docs page of recipes, release notes. |

Estimated 6–8 weeks of focused single-developer work.
