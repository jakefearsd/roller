# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Basic Build Commands
```bash
# Full build with tests
mvn clean install

# Build without tests (faster for development)
mvn -DskipTests=true install

# Run development server with embedded Derby database
cd app && mvn jetty:run
# Access at http://localhost:8080/roller

# Run with Docker and PostgreSQL
docker-compose up
```

### Testing Commands
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=TestClassName

# Run tests with Derby database integration
mvn test -Dtest.database=derby
```

### Development Database Setup
- **Development**: Uses embedded Derby database on port 4224
- **Testing**: In-memory Derby database
- **Docker**: PostgreSQL database via docker-compose
- **JNDI Name**: `jdbc/rollerdb`

## Architecture Overview

Apache Roller is a multi-user blog server built with:
- **Web Framework**: Apache Struts 2 with custom action hierarchy
- **Security**: Spring Security with role-based access control
- **Persistence**: JPA with EclipseLink (supports multiple databases)
- **Templating**: Dual system - Velocity for blog rendering, JSP/Tiles for admin UI
- **Search**: Apache Lucene for full-text search
- **DI Container**: Google Guice

### Core Package Structure
```
org.apache.roller.weblogger.
├── business/           # Service layer and business logic
│   ├── jpa/           # JPA persistence implementations
│   ├── plugins/       # Plugin system for content processing
│   ├── themes/        # Theme and template management
│   └── search/        # Lucene search implementation
├── pojos/             # Domain model entities
├── ui.struts2/        # Struts2 actions and web layer
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
- **Authentication**: Multiple providers (database, LDAP, OpenID)
- **Authorization**: Role-based with `GlobalPermission`, `WeblogPermission`, and `ObjectPermission`
- **Custom Interceptors**: `UISecurityInterceptor` enforces access controls
- **CSRF Protection**: Custom salt-based protection

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
- **`db-utils/`** - Database utilities and Derby lifecycle management
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
- **Development**: Uses Derby, theme reload enabled, caching disabled
- **Production**: Typically MySQL/PostgreSQL, optimized caching, theme compilation

## Plugin System
Roller supports plugins for:
- **Entry Plugins**: Content processing and formatting
- **Comment Plugins**: Comment filtering and spam protection
- **UI Plugins**: Editor components and custom functionality

Plugins implement specific interfaces and are configured through the plugin manager system.