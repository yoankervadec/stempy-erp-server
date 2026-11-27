# Stempy ERP Server

A modular ERP (Enterprise Resource Planning) backend for small and medium businesses, written in Java.

Stempy ERP is first and foremost a **learning project**. It started as a PHP / HTML / CSS / JavaScript app, was rebuilt with Node.js and React, and is now being rewritten in Java. The goal is less a finished product than the journey: learning by building, trying ideas as they come up, and refactoring when a better way shows up.

It is still built with real business needs in mind: the first place it is meant to be used is a sawmill. Every business works differently, so the core is kept generic and open. New domains plug in as modules, and adding a plain entity takes a few declarations rather than a full stack of classes.

The code tries to follow real-world practices: explicit layering, enforced module boundaries, transactions, migrations, permissions, and tests against a real database.

---

## Status

Early and evolving. Expect breaking changes.

**Working today**

- Multi-company users: one account can belong to several companies, with roles and per-user GRANT/DENY overrides per company
- Authentication with opaque server-side sessions (revocable), PBKDF2 password hashing, login throttling
- Role-based permissions, down to individual fields
- A declarative entity framework: describe an entity once and get safe, filterable, sortable, paginated queries plus create / update / soft delete endpoints
- Optimistic locking (`version`), soft deletes, RFC 7807 error responses, request ids in logs
- Master data: products and product categories
- A scheduler for recurring jobs (cron or fixed interval, per company or install-wide), configured in the database and editable while the server runs, safe with several server instances, with retries, timeouts, "run now" and run history (see [docs/API_GUIDE.md](docs/API_GUIDE.md) §7)
- An outbox for work that must follow a business transaction without breaking it (emails, webhooks): async event subscribers, delivered after the commit with retries, safe with several server instances
- Flyway database migrations run at startup
- Tests: unit, end-to-end against MySQL, and ArchUnit rules that enforce module boundaries

**Planned** (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), section 14)

- Customers, vendors, companies, taxes
- General ledger (accounts, periods, journal posting, trial balance)
- Sales, purchasing, inventory, AR / AP, banking, fixed assets, approvals
- Audit log, document numbering

---

## Tech stack

| Area        | Choice                                  |
| ----------- | --------------------------------------- |
| Language    | Java 25                                 |
| Build       | Maven (multi-module, wrapper included)  |
| Web         | Javalin 7                               |
| JSON        | Jackson                                 |
| Database    | MySQL 8, HikariCP, Flyway               |
| Logging     | SLF4J + Logback                         |
| Tests       | JUnit, ArchUnit                         |

---

## Project layout

```
stempy-erp-server/
|-- shared/     Tiny generic value types (empty for now)
|-- platform/   Infrastructure: database, web, entities and queries, security, events, migrations
|-- modules/    Business domains (users, masterdata.items, ...)
|-- app/        Entry point, configuration loading, CLI commands
`-- docs/       Architecture reference, API guide, Postman collection
```

Dependencies only flow one way: `app -> modules -> platform -> shared`.

Inside a business module, work goes through three layers: **Orchestrator -> Operation -> Repository**. The full set of rules lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Getting started

### Prerequisites

- JDK 25
- MySQL 8 running locally

### Configuration

Database connection settings and secrets come from environment variables or a `.env` file at the repository root (git-ignored). Start from the template:

```bash
cp .env.example .env    # then edit the values
```

It holds `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `TEST_DATABASE_NAME` (MySQL tests only; it must end in `_test`).

Other settings (server port, session lifetimes, login lockout) live in [app/src/main/resources/application.properties](app/src/main/resources/application.properties).

### Build and run

```bash
./mvnw package -DskipTests
java -jar app/target/stempy-erp-server.jar      # http://localhost:8080
```

Migrations run automatically on startup.

### Development script

`./dev` wraps the usual loops (`./dev help` lists everything):

```bash
./dev start | stop | restart | status   # background server; restart keeps the old one up if the build fails
./dev run                              # foreground server, Ctrl+C to stop
./dev compile                          # quick compile check
./dev test [Pattern]                   # e.g. ./dev test 'CustomersE2ETest#creates*'
./dev verify                           # full build + tests, MySQL required
./dev logs | warn                      # follow the main log or the WARN/ERROR log
./dev errors [days] | grep <re> [days] | req <requestId>   # search current and archived logs
./dev db                               # mysql shell on the dev database from .env
```

The background server runs from a copy of the jar in `.run/`, so rebuilding (including `./dev verify`) does not pull classes out from under it.

### Logs

The server logs to the console and to `logs/` (set `LOG_DIR` to move it):

- `logs/stempy-erp.log`: INFO and above, rolled daily or at 50MB, gzipped into `logs/archive/`, kept 90 days (3GB cap)
- `logs/stempy-erp-warn.log`: WARN and ERROR only, kept 1 year (1GB cap)

Each line carries the request id (`[...]` after the thread name), so `./dev req <id>` pulls up one whole request. Configuration: [app/src/main/resources/logback.xml](app/src/main/resources/logback.xml).

### Create a first user

There is no command to create users or companies yet, so on a fresh database insert the first ones by hand (after the server has started once, so the tables exist):

```sql
INSERT INTO companies (code, description, is_enabled) VALUES ('MYCO', 'My company', TRUE);

-- The first user is its own creator: created_by / updated_by point at itself.
-- It takes the next free id: the server may already have created its SYSTEM user.
INSERT INTO users (id, user_no, name, created_by, updated_by)
SELECT n.id, 'ADMIN', 'Administrator', n.id, n.id
FROM (SELECT COALESCE(MAX(id), 0) + 1 AS id FROM users) n;
```

Then give it a password and the built-in `ADMINISTRATOR` role in that company:

```bash
java -jar app/target/stempy-erp-server.jar set-password ADMIN                  # prompts, no echo
java -jar app/target/stempy-erp-server.jar assign-role ADMIN MYCO ADMINISTRATOR
```

`assign-role` also adds the company membership and makes it the user's default company.

Then log in with `POST /auth/login`. See [docs/API_GUIDE.md](docs/API_GUIDE.md) for the request formats, or import the Postman collection in [docs/postman/](docs/postman/).

### Run the tests

```bash
./mvnw test
```

The MySQL end-to-end tests use the database named by `TEST_DATABASE_NAME` and are skipped if it is unavailable. Before merging to main, require it, so an unreachable database fails the build instead of skipping every MySQL test:

```bash
./mvnw verify -Dtests.requireDb=true
```

---

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): layout, layer rules, entity framework, permissions model, roadmap
- [docs/API_GUIDE.md](docs/API_GUIDE.md): how to call the API by hand

---

## Feedback

This is a personal learning project, so I am not looking for large pull requests. Feedback is very welcome, though: if you spot a bug, a security issue, a design that could be simpler, or a Java idiom I am missing, feel free to open an issue.

---

## License

[MIT](LICENSE)
