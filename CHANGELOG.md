# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [0.1.92] - 2026-03-19

### Added
- Offsite backup script (daily pg_dump from production to backup server via SSH)
- External monitoring script (API availability check from backup server)
- Telegram alerts on backup failure (backup.sh, offsite-backup.sh)
- Backup status display in Telegram bot `/status` command
- `.env.example` template for server configuration
- SSH key-based server authentication (ed25519)

### Changed
- Server migration from FirstByte (1 GB RAM) to ZetaLink (4 GB RAM)
- Server params configurable via environment variables (JVM heap, Tomcat threads, HikariCP pool, PostgreSQL tuning)
- `docker-compose.yml` defaults optimized for 4 GB RAM
- `setup-server.sh` reads POSTGRES_PASSWORD from `.env`
- Monitor script checks API via HTTPS instead of HTTP (avoids 301 redirect)

## [0.1.15] - 2026-03-17

### Added
- Cascade delete account: admin transfer, empty list cleanup, public tasks reassigned to system user (id=0)
- System "Deleted User" (id=0) via Liquibase migration 008
- Privacy policy page (`/privacy`) and terms of service (`/terms`) via nginx
- `created_at` field in `todo_users` table (migration 007)
- Email verification and password reset (SMTP via reg.ru, SHA-256 token hashing)
- Change email endpoint with re-verification
- Rate limiting for email endpoints (forgot-password, verify-email, resend-verification, change-email)

### Changed
- ApiSecurityConfig: explicit permitAll list instead of `/api/auth/**`
- Postman collection made idempotent with `runId` pattern, added Delete Account Cascade folder

### Fixed
- Hibernate cache flush bug: `@Modifying(clearAutomatically=true, flushAutomatically=true)` on JPQL queries
- Admin transfer lost due to dirty entity cleared by `clearAutomatically` — fixed with `saveAndFlush()`

### Security
- Email tokens stored as SHA-256 hash in database
- DataIntegrityViolationException handler returns HTTP 409 (prevents information leak)

## [0.1.11] - 2026-03-16

### Added
- MIT license
- `@WebMvcTest` tests for TaskListController (28 tests)
- Access control for todo operations: verify list membership on update, delete, done, undone
- SMTP email infrastructure with health check monitoring
- Daily PostgreSQL backup script (`monitoring/backup.sh`, cron 3:00, 7-day retention)
- Production logging profile (INFO for app, WARN for Hibernate SQL)
- PostgreSQL password moved to environment variable

### Security
- Rate limiting with Bucket4j: login 5/min, register 3/hr, refresh 10/min, API 100/min
- User access control: restrict update/delete to own account

## [0.1.1] - 2026-03-08

### Added
- Telegram server monitoring: auto-alerts (cron 5 min), interactive bot (`/status`, `/restart`, `/logs`, `/errors`)
- PostgreSQL healthcheck with auto-fix for pg_hba.conf

### Changed
- Unified deploy via `docker compose` instead of `docker run`
- Optimized JVM heap (160 MB) and PostgreSQL memory for 1 GB RAM server
- Tomcat thread pool limited to 30

### Fixed
- PostgreSQL password desync between container restarts
- PostgreSQL auth: trust for Docker network connections

## [0.1.0] - 2026-03-06

### Added
- HTTPS with Let's Encrypt SSL (todo.mngerasimenko.ru)
- List deletion with membership checks and auto-admin assignment
- External Docker network and volumes to avoid prefix conflicts

## [0.0.9] - 2026-03-01

### Added
- Swagger UI / OpenAPI documentation (`/api/swagger-ui.html`)
- Liquibase database migrations (changelog-master + 6 changesets)
- `@Version` optimistic locking on all entities
- Concurrency tests with TestContainers
- Postman collection with 35 idempotent tests

### Changed
- JWT validation errors downgraded from ERROR to WARN
- NoResourceFoundException handled as 404 instead of 500

### Security
- TOCTOU race condition fixes via UNIQUE constraints

## [0.0.8] - 2026-02-26

### Removed
- Vaadin UI completely removed (Phase 5: pure REST API backend)

## [0.0.7] - 2026-02-23

### Added
- REST endpoints for React SPA (`/api/todos`, `/api/users`, `/api/lists`)
- CORS support for localhost:5173, localhost:3000

## [0.0.6] - 2026-02-21

### Added
- Server status endpoint (`/api/status`) with version and min Android version
- Android compatibility check (min_android_version)

## [0.0.5] - 2026-02-20

### Added
- Task lists system (create, join by password, roles ADMIN/USER)
- BCrypt password hashing
- User colors for task visualization
- Private tasks (visible only to creator)
- Task completion date and completor tracking

## [0.0.4] - 2026-02-17

### Added
- JWT authentication for REST API (login, register, refresh)
- Postman collection with environments (Local, Production)
- Two independent security chains: JWT (API) + Session (Vaadin UI)

### Security
- PostgreSQL JDBC driver updated 42.6.0 → 42.7.10 (CVE-2024-1597, CVSS 10.0)

## [0.0.3] - 2026-02-16

### Changed
- Spring Boot upgraded 3.2.2 → 3.5.6
- Vaadin upgraded 24.3.8 → 24.9.10

## [0.0.2] - 2026-02-10

### Added
- DTO pattern (Request → Dto → Response)
- UserDto, TodoDto with validation
- ResponseEntity wrappers for all API endpoints
- JaCoCo code coverage (70/70/60/80%)
- Service layer unit tests

## [0.0.1] - 2024-02-11

### Added
- Initial project: Spring Boot + Vaadin + H2
- Todo CRUD operations
- User management
- Docker deployment with CI/CD (GitHub Actions)
