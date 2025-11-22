# Docker Deployment Guide for Apache Roller

This guide provides comprehensive instructions for deploying Apache Roller using Docker in production environments, including detailed backup and restore procedures.

## Table of Contents

1. [Why Deploy Roller with Docker](#why-deploy-roller-with-docker)
2. [Architecture Overview](#architecture-overview)
3. [Production Deployment Setup](#production-deployment-setup)
4. [Configuration Management](#configuration-management)
5. [Database Backup and Restore](#database-backup-and-restore)
6. [Monitoring and Maintenance](#monitoring-and-maintenance)
7. [Scaling and Performance](#scaling-and-performance)
8. [Security Considerations](#security-considerations)
9. [Troubleshooting](#troubleshooting)

## Why Deploy Roller with Docker

### Benefits of Docker Deployment

**Consistency and Reproducibility**
- Eliminates "works on my machine" issues
- Identical environment across development, staging, and production
- Version-controlled infrastructure as code

**Simplified Deployment**
- Single command deployment with docker-compose
- Automatic dependency resolution (database drivers, mail libraries)
- Built-in service orchestration and health monitoring

**Isolation and Security**
- Application runs in isolated containers
- Network segmentation between services
- Controlled resource allocation and limits

**Maintenance and Updates**
- Easy rollback to previous versions
- Minimal downtime during updates
- Standardized backup and restore procedures

**Scalability**
- Horizontal scaling with container orchestration
- Load balancing across multiple instances
- Database connection pooling optimization

## Architecture Overview

### Docker Compose Services

```yaml
services:
  postgresql:     # Database service
  roller:         # Web application service
  nginx:          # Reverse proxy (optional)
  backup:         # Automated backup service (custom)
```

### Container Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Nginx Proxy   │    │  Roller WebApp  │    │  PostgreSQL DB  │
│   (Port 80/443) │────│   (Port 8080)   │────│   (Port 5432)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         │              ┌─────────────────┐              │
         │              │  Shared Volumes │              │
         │              │  - Media Files  │              │
         └──────────────│  - Logs        │──────────────┘
                        │  - Backups     │
                        │  - SSL Certs   │
                        └─────────────────┘
```

## Production Deployment Setup

### Directory Structure

```bash
/opt/roller-docker/
├── docker-compose.yml
├── .env
├── nginx/
│   ├── nginx.conf
│   └── ssl/
├── roller/
│   ├── config/
│   │   └── roller-custom.properties
│   └── logs/
├── postgresql/
│   ├── data/
│   └── initdb/
├── volumes/
│   ├── mediafiles/
│   ├── searchindex/
│   └── backups/
└── scripts/
    ├── backup.sh
    ├── restore.sh
    └── healthcheck.sh
```

### Enhanced Docker Compose Configuration

Create `/opt/roller-docker/docker-compose.yml`:

```yaml
version: '3.8'

services:
  postgresql:
    image: postgres:14
    container_name: roller-postgresql
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME:-rollerdb}
      POSTGRES_USER: ${DB_USER:-scott}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-tiger}
      POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale=en_US.UTF-8"
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - ./postgresql/data:/var/lib/postgresql/data
      - ./postgresql/initdb:/docker-entrypoint-initdb.d
      - ./volumes/backups:/backups
    networks:
      - roller-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER:-scott} -d ${DB_NAME:-rollerdb}"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  roller:
    build: .
    container_name: roller-webapp
    restart: unless-stopped
    depends_on:
      postgresql:
        condition: service_healthy
    environment:
      - STORAGE_ROOT=/var/lib/roller/data
      - DATABASE_JDBC_DRIVERCLASS=org.postgresql.Driver
      - DATABASE_JDBC_CONNECTIONURL=jdbc:postgresql://postgresql:5432/${DB_NAME:-rollerdb}
      - DATABASE_JDBC_USERNAME=${DB_USER:-scott}
      - DATABASE_JDBC_PASSWORD=${DB_PASSWORD:-tiger}
      - DATABASE_HOST=postgresql:5432
      - JAVA_OPTS=-Xmx${ROLLER_MEMORY:-1g} -Djava.awt.headless=true
    ports:
      - "127.0.0.1:8080:8080"
    volumes:
      - ./volumes/mediafiles:/var/lib/roller/data/mediafiles
      - ./volumes/searchindex:/var/lib/roller/data/searchindex
      - ./roller/config/roller-custom.properties:/usr/local/tomcat/lib/roller-custom.properties:ro
      - ./roller/logs:/usr/local/tomcat/logs
    networks:
      - roller-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/roller/healthcheck"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 120s

  nginx:
    image: nginx:alpine
    container_name: roller-nginx
    restart: unless-stopped
    depends_on:
      - roller
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro
      - ./volumes/mediafiles:/var/www/mediafiles:ro
    networks:
      - roller-network
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost/health"]
      interval: 30s
      timeout: 10s
      retries: 3

networks:
  roller-network:
    driver: bridge

volumes:
  postgresql-data:
  roller-mediafiles:
  roller-searchindex:
```

### Environment Configuration

Create `/opt/roller-docker/.env`:

```bash
# Database Configuration
DB_NAME=rollerdb
DB_USER=scott
DB_PASSWORD=your_secure_password_here

# Application Configuration
ROLLER_MEMORY=2g
SITE_URL=https://yourdomain.com
ADMIN_EMAIL=admin@yourdomain.com

# SSL Configuration
SSL_CERT_PATH=./nginx/ssl/cert.pem
SSL_KEY_PATH=./nginx/ssl/key.pem

# Backup Configuration
BACKUP_RETENTION_DAYS=30
BACKUP_SCHEDULE="0 2 * * *"
```

## Configuration Management

### Complete Roller Configuration

Create `/opt/roller-docker/roller/config/roller-custom.properties`:

```properties
# Installation Configuration
installation.type=auto

# Database Configuration
database.configurationType=jdbc
database.jdbc.driverClass=org.postgresql.Driver
database.jdbc.connectionURL=jdbc:postgresql://postgresql:5432/rollerdb
database.jdbc.username=scott
database.jdbc.password=tiger

# File Storage Configuration
mediafiles.storage.dir=/var/lib/roller/data/mediafiles
search.index.dir=/var/lib/roller/data/searchindex

# Site Configuration
site.name=Your Roller Site
site.shortName=Blog
site.description=Your blog powered by Apache Roller
site.adminemail=admin@yourdomain.com
site.absoluteurl=https://yourdomain.com

# Security Configuration
authentication.method=db
passwds.encryption.enabled=true
passwds.encryption.algorithm=bcrypt
users.registration.enabled=false
securelogin.enabled=true
weblogAdminsUntrusted=true

# Cache Configuration
cache.defaultFactory=org.apache.roller.weblogger.business.utils.cache.ExpiringLRUCacheFactoryImpl
cache.sitewide.enabled=true
cache.sitewide.size=100
cache.sitewide.timeout=1800
cache.weblogpage.enabled=true
cache.weblogpage.size=400
cache.weblogpage.timeout=3600

# Mail Configuration (SMTP)
mail.configurationType=properties
mail.hostname=smtp.yourdomain.com
mail.port=587
mail.username=noreply@yourdomain.com
mail.password=smtp_password

# Upload Configuration
uploads.enabled=true
uploads.types.allowed=jpg,jpeg,gif,png,pdf,doc,docx,xls,xlsx,ppt,pptx,txt,zip
uploads.types.forbid=exe,jsp,jspx,sh,bat,php,asp
uploads.file.maxsize=5.00
uploads.dir.maxsize=100.00

# Comment Configuration
users.comments.enabled=true
users.comments.htmlenabled=false
users.moderation.required=true
comment.throttle.enabled=true
comment.throttle.threshold=25

# Search Configuration
search.enabled=true
search.index.comments=true

# Theme Configuration
themes.customtheme.allowed=true
themes.reload.mode=false

# Logging Configuration
log4j.appender.roller.File=/usr/local/tomcat/logs/roller.log
log4j.appender.roller.MaxFileSize=2MB
log4j.appender.roller.MaxBackupIndex=5

# Performance Configuration
compression.gzipResponse.enabled=true
```

### Nginx Reverse Proxy Configuration

Create `/opt/roller-docker/nginx/nginx.conf`:

```nginx
events {
    worker_connections 1024;
}

http {
    upstream roller {
        server roller:8080;
    }

    # Rate limiting
    limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;
    limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

    server {
        listen 80;
        server_name yourdomain.com www.yourdomain.com;
        return 301 https://$host$request_uri;
    }

    server {
        listen 443 ssl http2;
        server_name yourdomain.com www.yourdomain.com;

        ssl_certificate /etc/nginx/ssl/cert.pem;
        ssl_certificate_key /etc/nginx/ssl/key.pem;
        ssl_protocols TLSv1.2 TLSv1.3;
        ssl_ciphers ECDHE-RSA-AES256-GCM-SHA512:DHE-RSA-AES256-GCM-SHA512;
        ssl_prefer_server_ciphers off;

        client_max_body_size 50M;

        # Security headers
        add_header X-Frame-Options DENY;
        add_header X-Content-Type-Options nosniff;
        add_header X-XSS-Protection "1; mode=block";
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains";

        # Rate limiting for login
        location /roller/roller-ui/login {
            limit_req zone=login burst=5 nodelay;
            proxy_pass http://roller;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }

        # Static media files served directly by nginx
        location /roller/resources/ {
            alias /var/www/mediafiles/;
            expires 30d;
            add_header Cache-Control "public, no-transform";
        }

        # Main application
        location / {
            proxy_pass http://roller;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            
            # Timeouts
            proxy_connect_timeout 60s;
            proxy_send_timeout 60s;
            proxy_read_timeout 60s;
        }

        # Health check endpoint
        location /health {
            access_log off;
            return 200 "healthy\n";
            add_header Content-Type text/plain;
        }
    }
}
```

## Database Backup and Restore

### Automated Backup System

Create `/opt/roller-docker/scripts/backup.sh`:

```bash
#!/bin/bash

# Apache Roller Docker Backup Script
set -euo pipefail

# Configuration
BACKUP_DIR="/opt/roller-docker/volumes/backups"
CONTAINER_NAME="roller-postgresql"
DB_NAME="${DB_NAME:-rollerdb}"
DB_USER="${DB_USER:-scott}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
DATE=$(date +%Y%m%d_%H%M%S)

# Create backup directory
mkdir -p "$BACKUP_DIR"/{database,mediafiles,config,logs}

echo "Starting backup process at $(date)"

# 1. Database Backup
echo "Backing up PostgreSQL database..."
docker exec "$CONTAINER_NAME" pg_dump \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --verbose \
    --clean \
    --if-exists \
    --create \
    --format=custom \
    --no-password \
    --file="/backups/database/roller_db_$DATE.backup"

# Compress database backup
docker exec "$CONTAINER_NAME" bash -c "
    cd /backups/database && 
    gzip roller_db_$DATE.backup
"

# 2. SQL dump for cross-platform compatibility
echo "Creating SQL dump..."
docker exec "$CONTAINER_NAME" pg_dump \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --clean \
    --if-exists \
    --create \
    --no-password \
    --file="/backups/database/roller_db_$DATE.sql"

gzip "$BACKUP_DIR/database/roller_db_$DATE.sql"

# 3. Media Files Backup
echo "Backing up media files..."
tar -czf "$BACKUP_DIR/mediafiles/mediafiles_$DATE.tar.gz" \
    -C /opt/roller-docker/volumes/mediafiles . \
    2>/dev/null || echo "No media files to backup"

# 4. Search Index Backup
echo "Backing up search index..."
tar -czf "$BACKUP_DIR/config/searchindex_$DATE.tar.gz" \
    -C /opt/roller-docker/volumes/searchindex . \
    2>/dev/null || echo "No search index to backup"

# 5. Configuration Backup
echo "Backing up configuration..."
tar -czf "$BACKUP_DIR/config/config_$DATE.tar.gz" \
    -C /opt/roller-docker \
    docker-compose.yml .env roller/config nginx/nginx.conf

# 6. Application Logs Backup
echo "Backing up logs..."
tar -czf "$BACKUP_DIR/logs/logs_$DATE.tar.gz" \
    -C /opt/roller-docker/roller/logs . \
    2>/dev/null || echo "No logs to backup"

# 7. Create backup manifest
echo "Creating backup manifest..."
cat > "$BACKUP_DIR/manifest_$DATE.txt" <<EOF
Backup Date: $(date)
Database: roller_db_$DATE.backup.gz, roller_db_$DATE.sql.gz
Media Files: mediafiles_$DATE.tar.gz
Search Index: searchindex_$DATE.tar.gz
Configuration: config_$DATE.tar.gz
Logs: logs_$DATE.tar.gz
Roller Version: $(docker exec roller-webapp cat /usr/local/tomcat/webapps/ROOT/META-INF/MANIFEST.MF | grep Implementation-Version || echo "Unknown")
PostgreSQL Version: $(docker exec "$CONTAINER_NAME" psql --version)
EOF

# 8. Cleanup old backups
echo "Cleaning up old backups (older than $RETENTION_DAYS days)..."
find "$BACKUP_DIR" -type f -name "*.gz" -mtime +$RETENTION_DAYS -delete
find "$BACKUP_DIR" -type f -name "*.txt" -mtime +$RETENTION_DAYS -delete

# 9. Verify backup integrity
echo "Verifying backup integrity..."
if ! gzip -t "$BACKUP_DIR/database/roller_db_$DATE.backup.gz"; then
    echo "ERROR: Database backup verification failed!"
    exit 1
fi

echo "Backup completed successfully at $(date)"
echo "Backup files created:"
ls -lh "$BACKUP_DIR"/*/"*$DATE*"
```

### Comprehensive Restore Script

Create `/opt/roller-docker/scripts/restore.sh`:

```bash
#!/bin/bash

# Apache Roller Docker Restore Script
set -euo pipefail

# Check arguments
if [ $# -lt 1 ]; then
    echo "Usage: $0 <backup_date> [--force]"
    echo "Example: $0 20241213_143022"
    echo "Available backups:"
    ls -1 /opt/roller-docker/volumes/backups/database/ | grep "roller_db_" | sed 's/roller_db_//g' | sed 's/.backup.gz//g' | sort
    exit 1
fi

BACKUP_DATE="$1"
FORCE_RESTORE="${2:-}"
BACKUP_DIR="/opt/roller-docker/volumes/backups"
CONTAINER_NAME="roller-postgresql"
DB_NAME="${DB_NAME:-rollerdb}"
DB_USER="${DB_USER:-scott}"

# Verify backup files exist
if [ ! -f "$BACKUP_DIR/database/roller_db_$BACKUP_DATE.backup.gz" ]; then
    echo "ERROR: Backup file not found: roller_db_$BACKUP_DATE.backup.gz"
    exit 1
fi

# Safety check
if [ "$FORCE_RESTORE" != "--force" ]; then
    echo "WARNING: This will completely replace your current Roller installation!"
    echo "Current database will be dropped and recreated."
    echo "All current data will be lost."
    echo ""
    echo "To proceed, run: $0 $BACKUP_DATE --force"
    exit 1
fi

echo "Starting restore process for backup: $BACKUP_DATE"

# 1. Stop Roller application
echo "Stopping Roller application..."
docker-compose stop roller

# 2. Create database backup (safety measure)
echo "Creating safety backup of current database..."
SAFETY_DATE=$(date +%Y%m%d_%H%M%S)
docker exec "$CONTAINER_NAME" pg_dump \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --format=custom \
    --no-password \
    --file="/backups/database/safety_backup_$SAFETY_DATE.backup" \
    || echo "WARNING: Could not create safety backup"

# 3. Restore database
echo "Restoring database from backup..."

# Decompress backup
docker exec "$CONTAINER_NAME" bash -c "
    cd /backups/database && 
    gunzip -k roller_db_$BACKUP_DATE.backup.gz
"

# Drop existing database and recreate
docker exec "$CONTAINER_NAME" psql \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname=postgres \
    --no-password \
    --command="DROP DATABASE IF EXISTS $DB_NAME;"

docker exec "$CONTAINER_NAME" psql \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname=postgres \
    --no-password \
    --command="CREATE DATABASE $DB_NAME OWNER $DB_USER ENCODING 'UTF8';"

# Restore from backup
docker exec "$CONTAINER_NAME" pg_restore \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --verbose \
    --clean \
    --if-exists \
    --no-password \
    "/backups/database/roller_db_$BACKUP_DATE.backup"

# Cleanup decompressed backup
docker exec "$CONTAINER_NAME" rm "/backups/database/roller_db_$BACKUP_DATE.backup"

# 4. Restore media files
echo "Restoring media files..."
if [ -f "$BACKUP_DIR/mediafiles/mediafiles_$BACKUP_DATE.tar.gz" ]; then
    rm -rf /opt/roller-docker/volumes/mediafiles/*
    tar -xzf "$BACKUP_DIR/mediafiles/mediafiles_$BACKUP_DATE.tar.gz" \
        -C /opt/roller-docker/volumes/mediafiles/
else
    echo "WARNING: No media files backup found for $BACKUP_DATE"
fi

# 5. Restore search index
echo "Restoring search index..."
if [ -f "$BACKUP_DIR/config/searchindex_$BACKUP_DATE.tar.gz" ]; then
    rm -rf /opt/roller-docker/volumes/searchindex/*
    tar -xzf "$BACKUP_DIR/config/searchindex_$BACKUP_DATE.tar.gz" \
        -C /opt/roller-docker/volumes/searchindex/
else
    echo "WARNING: No search index backup found for $BACKUP_DATE"
fi

# 6. Restore configuration (optional)
echo "Configuration files backup available but not automatically restored."
echo "To restore configuration: tar -xzf $BACKUP_DIR/config/config_$BACKUP_DATE.tar.gz"

# 7. Set proper permissions
echo "Setting file permissions..."
chmod -R 755 /opt/roller-docker/volumes/mediafiles/
chmod -R 755 /opt/roller-docker/volumes/searchindex/

# 8. Start services
echo "Starting services..."
docker-compose up -d

# 9. Wait for services to be ready
echo "Waiting for services to start..."
sleep 30

# 10. Verify restore
echo "Verifying restore..."
if docker exec "$CONTAINER_NAME" psql \
    --host=localhost \
    --port=5432 \
    --username="$DB_USER" \
    --dbname="$DB_NAME" \
    --no-password \
    --command="SELECT COUNT(*) FROM roller_user;" > /dev/null; then
    echo "Database restore verified successfully"
else
    echo "ERROR: Database restore verification failed"
    exit 1
fi

echo "Restore completed successfully!"
echo "Please verify your Roller installation at: http://localhost:8080/roller"

# Display restore summary
if [ -f "$BACKUP_DIR/manifest_$BACKUP_DATE.txt" ]; then
    echo ""
    echo "Restore Summary:"
    cat "$BACKUP_DIR/manifest_$BACKUP_DATE.txt"
fi
```

### Automated Backup Scheduling

Create a systemd service for automated backups:

`/etc/systemd/system/roller-backup.service`:

```ini
[Unit]
Description=Apache Roller Backup Service
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
User=root
ExecStart=/opt/roller-docker/scripts/backup.sh
WorkingDirectory=/opt/roller-docker
```

`/etc/systemd/system/roller-backup.timer`:

```ini
[Unit]
Description=Run Roller backup daily at 2 AM
Requires=roller-backup.service

[Timer]
OnCalendar=daily
Persistent=true
AccuracySec=1m

[Install]
WantedBy=timers.target
```

Enable the backup timer:

```bash
sudo systemctl enable roller-backup.timer
sudo systemctl start roller-backup.timer
```

### Database Migration and Upgrade Procedures

When upgrading Roller versions:

```bash
#!/bin/bash
# upgrade.sh - Upgrade Roller to new version

OLD_VERSION="6.1.0"
NEW_VERSION="6.1.5"
BACKUP_DATE=$(date +%Y%m%d_%H%M%S)

# 1. Create full backup
echo "Creating pre-upgrade backup..."
./scripts/backup.sh

# 2. Pull new Docker image
echo "Pulling new Roller image..."
docker-compose pull

# 3. Stop services
echo "Stopping services..."
docker-compose down

# 4. Update docker-compose.yml if needed
echo "Review docker-compose.yml for any required changes"

# 5. Start database only
echo "Starting database for migration..."
docker-compose up -d postgresql

# 6. Wait for database
sleep 30

# 7. Run migration scripts if needed
echo "Check for migration scripts in /sql/ directory"

# 8. Start all services
echo "Starting all services..."
docker-compose up -d

# 9. Verify upgrade
echo "Verify upgrade at http://localhost:8080/roller"
```

## Monitoring and Maintenance

### Health Check Script

Create `/opt/roller-docker/scripts/healthcheck.sh`:

```bash
#!/bin/bash

# Health check script for Roller Docker deployment
set -euo pipefail

SITE_URL="http://localhost:8080/roller"
SMTP_HOST="smtp.yourdomain.com"
SMTP_PORT="587"

echo "=== Roller Health Check $(date) ==="

# 1. Container status
echo "Checking container status..."
docker-compose ps

# 2. Database connectivity
echo "Checking database connectivity..."
if docker exec roller-postgresql pg_isready -U scott -d rollerdb; then
    echo "✓ Database is accessible"
else
    echo "✗ Database connection failed"
    exit 1
fi

# 3. Web application response
echo "Checking web application..."
if curl -f -s "$SITE_URL" > /dev/null; then
    echo "✓ Web application is responding"
else
    echo "✗ Web application is not responding"
    exit 1
fi

# 4. Disk space
echo "Checking disk space..."
df -h /opt/roller-docker/volumes/

# 5. Memory usage
echo "Checking memory usage..."
docker stats --no-stream

# 6. Log errors
echo "Checking for recent errors..."
docker logs --tail=20 roller-webapp | grep -i error || echo "No recent errors found"

# 7. Database size
echo "Checking database size..."
docker exec roller-postgresql psql -U scott -d rollerdb -c "
    SELECT 
        pg_size_pretty(pg_database_size('rollerdb')) as database_size,
        (SELECT COUNT(*) FROM roller_user) as users,
        (SELECT COUNT(*) FROM weblog) as blogs,
        (SELECT COUNT(*) FROM weblogentry) as entries,
        (SELECT COUNT(*) FROM roller_comment) as comments;
"

echo "=== Health Check Complete ==="
```

### Log Rotation Configuration

Add to `/etc/logrotate.d/roller`:

```
/opt/roller-docker/roller/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
```

## Scaling and Performance

### Database Connection Pooling

Add to `roller-custom.properties`:

```properties
# Connection pool configuration
database.jdbc.maxConnections=20
database.jdbc.minConnections=5
database.jdbc.maxIdleTime=3600
database.jdbc.validationQuery=SELECT 1
```

### Load Balancing Multiple Instances

For high-traffic deployments, scale horizontally:

```yaml
# docker-compose.scale.yml
version: '3.8'

services:
  roller:
    deploy:
      replicas: 3
    environment:
      - ROLLER_INSTANCE_ID=${HOSTNAME}
    
  nginx:
    volumes:
      - ./nginx/nginx-lb.conf:/etc/nginx/nginx.conf:ro

  postgresql:
    environment:
      - POSTGRES_MAX_CONNECTIONS=100
```

Update nginx configuration for load balancing:

```nginx
upstream roller {
    server roller_1:8080;
    server roller_2:8080;
    server roller_3:8080;
}
```

### Performance Tuning

**JVM Optimization:**
```bash
JAVA_OPTS="-Xmx2g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC"
```

**Database Optimization:**
```sql
-- PostgreSQL performance tuning
shared_buffers = 256MB
effective_cache_size = 1GB
work_mem = 4MB
maintenance_work_mem = 64MB
checkpoint_completion_target = 0.9
```

## Security Considerations

### SSL/TLS Configuration

Generate SSL certificates:

```bash
# Self-signed certificate for testing
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /opt/roller-docker/nginx/ssl/key.pem \
    -out /opt/roller-docker/nginx/ssl/cert.pem

# Or use Let's Encrypt
certbot certonly --webroot -w /opt/roller-docker/nginx/html \
    -d yourdomain.com
```

### Security Hardening

1. **Database Security:**
   - Use strong passwords
   - Limit network access
   - Regular security updates

2. **Application Security:**
   - Enable HTTPS
   - Configure security headers
   - Regular vulnerability scans

3. **System Security:**
   - Firewall configuration
   - Regular updates
   - Log monitoring

### Firewall Configuration

```bash
# UFW firewall rules
ufw allow 22/tcp    # SSH
ufw allow 80/tcp    # HTTP
ufw allow 443/tcp   # HTTPS
ufw deny 5432/tcp   # PostgreSQL (deny external access)
ufw deny 8080/tcp   # Tomcat (deny external access)
ufw enable
```

## Troubleshooting

### Common Issues and Solutions

**Container won't start:**
```bash
# Check logs
docker-compose logs roller
docker-compose logs postgresql

# Check disk space
df -h

# Verify permissions
ls -la /opt/roller-docker/volumes/
```

**Database connection errors:**
```bash
# Test database connectivity
docker exec roller-postgresql psql -U scott -d rollerdb -c "SELECT 1"

# Check network connectivity
docker exec roller-webapp nc -zv postgresql 5432
```

**Performance issues:**
```bash
# Monitor resource usage
docker stats

# Check database queries
docker exec roller-postgresql psql -U scott -d rollerdb -c "
    SELECT query, calls, total_time, mean_time 
    FROM pg_stat_statements 
    ORDER BY total_time DESC LIMIT 10;
"
```

**Backup/restore issues:**
```bash
# Verify backup integrity
gzip -t /opt/roller-docker/volumes/backups/database/*.gz

# Check backup permissions
ls -la /opt/roller-docker/volumes/backups/

# Test restore in staging environment
./scripts/restore.sh backup_date --force
```

### Emergency Recovery Procedures

**Complete system failure:**
```bash
# 1. Restore from latest backup
./scripts/restore.sh $(ls -1 /opt/roller-docker/volumes/backups/database/ | grep roller_db | tail -1 | sed 's/roller_db_//g' | sed 's/.backup.gz//g') --force

# 2. If backup is corrupted, restore from older backup
ls -1 /opt/roller-docker/volumes/backups/database/ | grep roller_db

# 3. Manual database recovery
docker exec roller-postgresql pg_resetwal /var/lib/postgresql/data
```

**Data corruption:**
```bash
# Check database integrity
docker exec roller-postgresql psql -U scott -d rollerdb -c "
    SELECT datname, stats_reset, conflicts, deadlocks 
    FROM pg_stat_database 
    WHERE datname = 'rollerdb';
"

# Repair corrupted indexes
docker exec roller-postgresql psql -U scott -d rollerdb -c "REINDEX DATABASE rollerdb;"
```

This comprehensive guide provides everything needed to deploy, maintain, and backup Apache Roller using Docker in production environments. Regular backups, monitoring, and maintenance procedures ensure reliable operation and quick recovery capabilities.