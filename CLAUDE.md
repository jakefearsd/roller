# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Important Rules

- **Never commit or push unless explicitly asked.** Wait for the user to request a commit or push. Do not proactively create commits or push to remote.
- **Work directly on `master`.** This is a solo-developer repo; do not create a feature branch before committing unless explicitly asked.

## Build and Development Commands

### Basic Build Commands
```bash
# Full build with tests (tests need Docker: they run against a PostgreSQL container)
mvn clean install

# Build without tests (faster for development)
mvn -DskipTests=true install

# Run the dev server: starts PostgreSQL, applies migrations, runs Jetty
./roller dev
# Access at http://localhost:8083/roller

# Database-only helpers
./roller db          # start PostgreSQL and migrate, without running the app
./roller migrate     # apply pending migrations
./roller status      # show applied migrations
./roller stop        # stop the dev database (data preserved)
./roller reset       # DESTROY the dev database volume and rebuild it
```

### Testing Commands
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=TestClassName

# Coverage report (JaCoCo)
mvn clean test && mvn jacoco:report -pl app
# HTML: app/target/site/jacoco/index.html
```

Tests require Docker. A single PostgreSQL container is started once per JVM by
`RollerTestBootstrap` (a JUnit `LauncherSessionListener`) and its schema is built
by applying the real `bin/db/migrations` chain — there is no separate test
schema. `RollerDatabaseExtension` truncates all data tables before each test, so
tests do not need to unwind their own fixtures.

### Database

Roller is **PostgreSQL-only** as of 6.2.0. Development, test, and production all
run the same engine; the previous Derby-in-test / PostgreSQL-in-prod split and
the Velocity/Texen layer that generated DDL for seven vendors are gone.

- **Development**: PostgreSQL 16 via `docker-compose.yml` (named volume, data persists)
- **Testing**: PostgreSQL 16 via Testcontainers, schema from the migration chain
- **JNDI Name**: `jdbc/rollerdb`

#### Schema changes

**Every commit that changes the schema MUST add a numbered migration** under
`bin/db/migrations/`. Take the next `V<NNN>__description.sql`, write idempotent
DDL, and never edit a migration that has already been applied anywhere but local
dev — fix mistakes with a follow-up migration. See
`bin/db/migrations/README.md` for the full convention; `SchemaMigrationTest`
enforces discoverability, schema shape, and idempotency.

Migrations reach a database three ways, all reading the same files:
`bin/db/migrate.sh` (deploy), `DatabaseInstaller` (web install wizard), and the
test harness.

## Architecture Overview

Apache Roller is a multi-user blog server built with:
- **Web Framework**: Spring MVC with `@Controller` classes and `*.rol` URL mappings
- **Security**: Spring Security with role-based access control and built-in CSRF
- **Persistence**: JPA with EclipseLink on PostgreSQL
- **Templating**: Dual system - Velocity for blog rendering, JSP/JSTL for admin UI
- **Search**: Apache Lucene for full-text search
- **DI Container**: Google Guice (business layer), Spring (web layer)

### Core Package Structure
```
org.apache.roller.weblogger.
├── business/           # Service layer and business logic
│   ├── jpa/           # JPA persistence implementations
│   ├── plugins/       # Plugin system for content processing
│   ├── themes/        # Theme and template management
│   └── search/        # Lucene search implementation
├── pojos/             # Domain model entities
├── ui/controllers/    # Spring MVC controllers
│   ├── admin/         # Administrative functions
│   ├── core/          # Core app functions (login, profile)
│   └── editor/        # Content editing interface
└── util/              # Common utilities
```

### Key Architecture Patterns

**Service Layer Pattern**: The `Weblogger` interface serves as the main facade providing access to all manager components:
```java
UserManager getUserManager()
WeblogManager getWeblogManager()
WeblogEntryManager getWeblogEntryManager()
ThemeManager getThemeManager()
IndexManager getIndexManager()
// ... other managers
```

**Manager Pattern**: Business logic is organized into specialized managers:
- `UserManager` - User accounts and authentication
- `WeblogManager` - Blog CRUD operations  
- `WeblogEntryManager` - Blog entry management
- `ThemeManager` - Theme and template handling
- `IndexManager` - Search indexing
- `MediaFileManager` - File uploads and media

### Security Architecture
- **Authentication**: Multiple providers (database, LDAP)
- **Authorization**: Role-based with `GlobalPermission`, `WeblogPermission`, and `ObjectPermission`
- **Custom Interceptor**: `RollerHandlerInterceptor` enforces access controls
- **CSRF Protection**: Spring Security built-in CSRF (automatic on all POST forms)

### Theme System
- **Shared Themes**: System-provided themes in `/themes/` directory
- **Custom Themes**: User-customized themes per blog
- **Template Types**: Main templates (`.vm`), stylesheets, and resources
- **Hot Reload**: Theme changes reload automatically in development mode

### Database Schema
Key domain entities:
- `Weblog` - Blog instances with settings and metadata
- `WeblogEntry` - Individual blog posts with content and publishing status
- `User` - User accounts with roles and permissions
- `WeblogCategory` - Blog categorization
- `WeblogEntryComment` - Comment system
- `MediaFile` - File attachments and media
- `WeblogTemplate` - Custom template definitions

### Search Implementation
- **Engine**: Apache Lucene with background indexing
- **Operations**: Asynchronous add/remove/rebuild operations
- **Scope**: Full-text search across entries with category and locale filtering
- **Index Location**: Configurable work directory for search indices

## Module Organization

- **`app/`** - Main web application (WAR artifact)
- **`bin/db/`** - Schema migrations and the migrate/install scripts
- **`it-selenium/`** - Integration tests (currently disabled)
- **`assembly-release/`** - Release packaging and distribution

## Configuration Files

### Key Configuration Locations
- **Jetty Development**: `app/src/test/resources/jetty.xml`
- **Test Properties**: `app/src/test/resources/roller-jettyrun.properties`
- **Security Config**: `app/src/main/webapp/WEB-INF/security.xml`
- **JPA Mappings**: `app/src/main/resources/META-INF/*.orm.xml`
- **Velocity Templates**: `app/src/main/webapp/WEB-INF/velocity/templates/`

### Development vs Production
- **Development**: PostgreSQL via docker-compose, theme reload enabled, caching disabled
- **Production**: PostgreSQL, optimized caching, theme compilation

## Plugin System
Roller supports plugins for:
- **Entry Plugins**: Content processing and formatting
- **Comment Plugins**: Comment filtering and spam protection
- **UI Plugins**: Editor components and custom functionality

Plugins implement specific interfaces and are configured through the plugin manager system.