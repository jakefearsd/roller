# Apache Roller

[Apache Roller](http://roller.apache.org) is a Java-based, full-featured, multi-user and group-blog server suitable for blog sites of any size. First created in 2002 and maintained by the Apache Software Foundation, Roller powers everything from personal blogs to large-scale multi-tenant blogging platforms.

**Current Version:** 6.1.5 | **License:** Apache 2.0 | **Java:** 11+

---

## Key Capabilities

### Multi-User Blogging
- Host unlimited weblogs on a single installation, each with its own URL, theme, and settings
- Role-based permissions per weblog — invite members as authors, editors, or administrators
- Designate any weblog as the site-wide front page
- User self-registration (configurable) with admin approval workflows

### Content Authoring
- Rich text and source-code editing of blog entries with draft, pending, and published states
- Schedule entries for future publication
- Organize content with categories and tags
- Upload and manage media files (images, podcasts, attachments) in folder hierarchies
- Entry plugins for automatic formatting: line-break conversion, code block encoding, email obfuscation, and smiley replacement

### Comments and Community
- Visitor comments with moderation and approval workflows
- Spam protection via pluggable validators: banned-word lists, excessive-link detection, size limits, Akismet integration
- Anti-bot authenticators including CAPTCHA-style math challenges
- Trackback and pingback protocol support

### Themes and Templates
- Five built-in themes: **basic**, **basicmobile**, **gaurav**, **fauxcoly**, and **frontpage**
- Apache Velocity template engine with a rich set of page models for full layout control
- In-app template editor for per-weblog customization
- Separate templates for main page, single-entry permalink, day archive, search results, and sidebar

### Search
- Full-text search powered by Apache Lucene with background indexing
- Search across entries and comments with category and locale filtering
- OpenSearch protocol support for browser search-bar integration

### Feeds and APIs
- RSS 2.0 and Atom 1.0 feeds for entries, comments, and search results
- **Atom Publishing Protocol (AtomPub)** — full CRUD for entries and media from any compliant client
- **XML-RPC** — Blogger API and MetaWeblog API for desktop blogging clients
- **OAuth 1.0a** for authorized third-party access
- **OpenSearch** description documents for search discovery

### Security and Authentication
- Pluggable authentication: database accounts, LDAP/Active Directory, OpenID, or container-managed
- Spring Security with role-based access control at global, weblog, and object levels
- BCrypt password hashing with configurable strength
- CSRF protection and WSSE for web-service security

### Planet Feed Aggregator
- Aggregate content from external RSS/Atom feeds into a unified view
- Manage subscription groups to create topic-based or team-based aggregations

### Administration
- Global configuration dashboard for site-wide settings
- Bulk comment management across all weblogs
- User administration: create, edit, disable, and assign global roles
- Ping target management for blog update notification services
- Built-in installation wizard with automatic database schema creation and migration

### Internationalization
- Full UI localization in 8 languages: English, German, Spanish, French, Japanese, Korean, Russian, and Simplified Chinese

---

## Supported Databases

| Database   | Use Case |
|------------|----------|
| PostgreSQL | Recommended for production |
| MySQL      | Production |
| Oracle     | Production |
| SQL Server | Production |
| DB2        | Production |
| Derby      | Embedded development and testing |

---

## Quick Start

### Option 1: Maven + Embedded Derby (development)

```bash
git clone https://github.com/apache/roller.git
cd roller
mvn -DskipTests=true install
cd app && mvn jetty:run
```

Browse to http://localhost:8080/roller

### Option 2: Docker Compose + PostgreSQL

```bash
git clone https://github.com/apache/roller.git
cd roller
docker-compose up
```

Browse to http://localhost:8080/roller

---

## Project Structure

| Module             | Description |
|--------------------|-------------|
| `app/`             | Main web application (WAR) — Struts actions, JSP pages, Velocity templates, business logic |
| `docs/`            | Install, User, and Template guides in AsciiDoc format |
| `db-utils/`        | Database utilities and Derby lifecycle management |
| `it-selenium/`     | Selenium-based integration tests |
| `assembly-release/`| Release packaging and distribution |

---

## Documentation

Detailed guides are available in the [`docs/`](docs/) directory:

- **[Install Guide](docs/roller-install-guide.adoc)** — Server setup, database configuration, and deployment
- **[User Guide](docs/roller-user-guide.adoc)** — Blogging, media management, comments, and administration
- **[Template Guide](docs/roller-template-guide.adoc)** — Theme creation, Velocity templates, and page models

---

## Technology Stack

- **Web Framework:** Apache Struts 2
- **Security:** Spring Security
- **Persistence:** JPA (EclipseLink)
- **Templating:** Apache Velocity (blog rendering), JSP/Tiles (admin UI)
- **Search:** Apache Lucene
- **Feeds:** ROME (RSS/Atom)
- **DI:** Google Guice

---

## Contributing

- Dev mailing list: dev@roller.apache.org
- [How to build and run Roller](https://cwiki.apache.org/confluence/x/EM4)
- [How to contribute](https://cwiki.apache.org/confluence/x/2hsB)
- [Developer resources](https://cwiki.apache.org/confluence/x/D84)
