# Apache Roller

Roller is a Java-based, multi-user blog server. It is a fork of [Apache Roller](http://roller.apache.org), created in 2002 and maintained by the Apache Software Foundation, simplified substantially: PostgreSQL only, Markdown only, no comment subsystem, no Planet aggregator, and a Spring Boot executable WAR in place of the old servlet-container deployment.

**Current Version:** 0.1.0 | **License:** Apache 2.0 | **Java:** 25

---

## Key Capabilities

### Multi-User Blogging
- Host unlimited weblogs on a single installation, each with its own URL, theme, and settings
- Role-based permissions per weblog — grant an existing account `edit_draft`, `post`, or `admin` access (`WeblogPermission`)
- Designate any weblog as the site-wide front page (`WebloggerRuntimeConfig.isFrontPageWeblog`)
- Admin-managed user accounts: create, edit, disable, and assign global roles (`UserAdmin.jsp`)

### Content Authoring
- Rich text and source-code editing of blog entries with draft, pending, and published states
- Schedule entries for future publication
- Organize content with categories and tags
- Upload and manage media files (images, podcasts, attachments) in folder hierarchies (`MediaFileManager`)

### Themes and Templates
- Three built-in shared themes — **journal**, **portfolio**, and **travel** — plus **frontpage**, the multi-weblog aggregator theme (`themes/`)
- Apache Velocity template engine with a rich set of page models for full layout control
- In-app template editor for per-weblog customization
- Separate templates for main page, single-entry permalink, day archive, search results, and sidebar

### Search
- Full-text search powered by Apache Lucene with background indexing
- Search across entries with category and locale filtering

### Feeds
- Atom 1.0 feeds for weblog entries, hand-rendered by Velocity templates (`WEB-INF/velocity/templates/feeds/`)

### Security and Authentication
- Database-backed authentication only; the enum kept for LDAP/OpenID/container-managed values now fails loudly at startup instead of silently degrading (`AuthMethod`)
- Spring Security 7 with role-based access control at global, weblog, and object levels
- BCrypt password hashing with configurable strength
- Spring Security's built-in CSRF protection on all POST forms

### Administration
- Global configuration dashboard for site-wide settings
- User administration: create, edit, disable, and assign global roles
- Built-in installation wizard with automatic database schema creation and migration

### Internationalization
- UI message bundle in 8 languages: English (source of truth), German, Spanish, French, Japanese, Korean, Russian, and Simplified Chinese. Coverage varies by language; missing keys fall back to English at runtime (`MessageKeyTest`).

---

## Supported Databases

| Database   | Use Case |
|------------|----------|
| PostgreSQL | Development, testing, and production |

Roller has been PostgreSQL-only since this fork diverged. Earlier upstream releases generated vendor-specific
DDL for DB2, Derby, HSQLDB, SQL Server, MySQL and Oracle; that layer has been
removed in favour of a single, tested schema.

---

## Quick Start

### Option 1: Dev server (Spring Boot, embedded Tomcat)

```bash
git clone https://github.com/apache/roller.git
cd roller
mvn -DskipTests=true install
./roller dev     # starts PostgreSQL, applies migrations, runs spring-boot:run
```

Browse to http://localhost:8083/roller

Optional: install `cwebp` (package `webp` on Debian/Ubuntu, `libwebp-tools`
on Fedora, `brew install webp` on macOS) to get WebP renditions of uploaded
images locally. It is feature-detected — without it Roller serves a
JPEG/PNG-only rendition ladder and everything else works the same.

### Option 2: Run the packaged executable WAR directly

```bash
mvn -DskipTests=true -pl app install
java -jar app/target/roller.war --server.port=8083 \
    -Droller.custom.config=app/target/test-classes/roller-boot-dev.properties
```

No Maven plugin or IDE involved — the WAR embeds Tomcat and runs standalone
(requires a running, migrated PostgreSQL; `./roller db` starts one).

### Option 3: Docker / production stack

```bash
git clone https://github.com/apache/roller.git
cd roller
docker compose up -d   # starts PostgreSQL only, for local dev
./roller dev            # applies migrations, runs the app
```

Pre-built images are published to GHCR only when a `v*.*.*` tag is pushed —
pushing to master publishes nothing. For a real
deployment — the app itself containerized behind TLS, with automated backups
and one-command upgrades — see the production stack
(`docker-compose.prod.yml`, `deploy/deploy.sh`) and its runbook,
[`docker_deployment.md`](docker_deployment.md).

---

## Project Structure

| Path               | Description |
|---------------------|-------------|
| `app/`             | Main web application (executable WAR) — Spring MVC controllers, JSP admin pages, Velocity blog-rendering templates, business logic |
| `docs/`            | Install, User, and Template guides in AsciiDoc format |
| `bin/db/`          | Schema migrations and the migrate/install scripts |
| `deploy/`          | Production deploy script, Caddy/backup config for `docker-compose.prod.yml` |
| `it-selenium/`     | Browser-driven integration tests, run against the packaged WAR via `mvn verify -Pit` |

---

## Documentation

Detailed guides are available in the [`docs/`](docs/) directory:

- **[Install Guide](docs/roller-install-guide.adoc)** — Server setup, database configuration, and deployment
- **[User Guide](docs/roller-user-guide.adoc)** — Blogging, media management, and administration
- **[Template Guide](docs/roller-template-guide.adoc)** — Theme creation, Velocity templates, and page models
- **[Production Deployment Runbook](docker_deployment.md)** — Fresh-VPS Docker deployment, TLS, backups, upgrades

---

## Technology Stack

- **Runtime:** Spring Boot 4.1 executable WAR on embedded Tomcat 11, targeting Java 25
- **Web Framework:** Spring MVC
- **Security:** Spring Security 7
- **Persistence:** JPA (EclipseLink 5)
- **Database:** PostgreSQL only
- **Templating:** Apache Velocity (blog rendering), JSP/JSTL (admin UI)
- **Search:** Apache Lucene
- **DI:** Single Spring container (business beans in `WebloggerBeanConfig`, wired via the `Weblogger` facade)

---

## Testing

- ~2,985 JUnit tests run against a real PostgreSQL container (Testcontainers) —
  no mocked persistence layer. `mvn test` (needs Docker).
- JaCoCo coverage floors are enforced at `verify` and only ever move up; see
  `CLAUDE.md` for the coverage-gate and diff-coverage commands.
- Browser integration tests (`it-selenium/`) drive the packaged executable WAR
  end to end; run them with `mvn verify -Pit`.

## Contributing

- Dev mailing list: dev@roller.apache.org
- [How to build and run Roller](https://cwiki.apache.org/confluence/x/EM4)
- [How to contribute](https://cwiki.apache.org/confluence/x/2hsB)
- [Developer resources](https://cwiki.apache.org/confluence/x/D84)
