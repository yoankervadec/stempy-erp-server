# Stempy ERP Server: Package Structure Reference

A reference for the project layout, layer rules, and conventions: both **what exists today** and **the target shape** new code should grow into.

_Last synced with the code: 2026-11-23 (after the scheduler, §17)._

**Stack (as declared in the root `pom.xml`):** Java 25 (enforced `[25,26)`), Maven multi-module, Javalin 7, Jackson 2.17, HikariCP 7, **MySQL 8** (Connector/J), Flyway 11 (`flyway-mysql`), slf4j + logback, JUnit 6.

Logging: slf4j + logback-classic (app/src/main/resources/logback.xml prints the request id). Declared but not used yet (kept for planned work): Lettuce (Redis: a shared login throttle if the server is ever replicated; the scheduler does NOT need it, see §17.1), Testcontainers BOM (in dependencyManagement only). ArchUnit enforces the module rules (§11). Authentication uses opaque server-side sessions, not JWT (see §3.4); jjwt was removed.

---

## 1. Guiding principles

1. **Package-by-module at the top level, package-by-layer inside each module.** Business domains (sales, gl, inventory...) never bleed into each other; inside each, the layers are consistent.
2. **Three-layer business flow:** Orchestrator -> Operation -> Repository.
3. **`UnitOfWork` is passed explicitly** to every layer. No hidden ThreadLocal state, so every method's dependencies are visible in its signature and tests are trivial.
4. **Modules talk to each other in exactly two ways:**
   - Direct call to another module's _public API_ (an interface published in the `ServiceRegistry`, e.g. `UsersApi`).
   - Domain events (something happened, react if you care). GL will be wired this way.
   - Entities may also declare **relations** to another module's entity (`Product.CREATED_BY_REL -> User.DEF`) for read-side filtering/sorting. That is a data dependency, so module order in `AllModules` matters (§4.1).
5. **Financial side effects are synchronous and in-transaction.** If a GL posting fails, the originating business transaction fails too. Non-critical work (emails, webhooks, search indexing) goes through the outbox and is processed asynchronously.
6. **Business code is HTTP-agnostic.** It throws `ErpException` subclasses carrying an `ErrorCategory`; only the web layer maps categories to status codes.
7. **Wiring is explicit and fails fast at startup.** Registries (`EntityRegistry`, `ServiceRegistry`, `EventBus`) are filled during startup, then frozen. No classpath scanning in production.
8. **Boundaries are enforced by tests (ArchUnit), not by memory.**  (dependency is declared, no rules yet)

---

## 2. Top-level layout

### 2.1 Maven modules

```
stempyerpserver/                 (parent POM, groupId com.lesconstructionssapete)
├── shared/      stempyerpserver-shared     Tiny generic value types. No business meaning.    empty
├── platform/    stempyerpserver-platform   Infrastructure + Flyway migrations. No business logic.
├── modules/     stempyerpserver-modules    Business domains.
├── app/         (app jar)                  Main: argument dispatch; AppSettings (config sources); set-password CLI.
└── docs/                                   ARCHITECTURE.md (this file).
```

**Maven dependency chain (enforced by the build):**

```
app  ->  modules  ->  platform  ->  shared
```

Because these are separate Maven artifacts, `platform` **cannot** import from `modules` and `shared` cannot import from either: the compiler enforces the top-level direction. What Maven does _not_ enforce is isolation **between** business modules (they all live in one `modules` artifact); that is ArchUnit's job (§9).

### 2.2 Java packages

Root package: `com.lesconstructionssapete.stempyerpserver`

```
com.lesconstructionssapete.stempyerpserver
├── app/            Main.java, SetPasswordCommand.java, config/AppSettings.java
├── platform/       db, entity, query, error, events, module, security, web, scheduler
├── modules/        Operation.java, common/ (Entities), + one package per business module (users, masterdata.items, system.scheduling)
└── shared/         (nothing yet)
```

**Allowed dependency direction between business modules:**

```
modules.X  ->  modules.Y.events          (event contracts only)
modules.X  ->  modules.Y.<Y>Api          (explicitly public service only)
modules.X  ->  modules.Y.<Entity>.DEF    (entity relations, read side only)
```

### 2.3 Configuration

| Source                                          | Holds                                                                    |
| ----------------------------------------------- | ------------------------------------------------------------------------ |
| `app/src/main/resources/application.properties` | `server.port`, Redis port (unused yet), `auth.*` session/login policy, `scheduler.*` (§17.6) |
| `platform/src/main/resources/db.properties`     | `database.pool.*`, `database.url.params.*` (JDBC URL), `database.mysql.performance.settings.*` (driver properties) |
| `.env` (repo root, git-ignored) + environment   | Secrets & per-machine: `DATABASE_HOST/PORT/NAME/USER/PASSWORD`. Overrides the files on a shared key. |

`app.config.AppSettings.load()` merges the three into one `platform.config.Settings` (typed getters: `get`, `getInt`, `getLong`, `getDuration`, `byPrefix`; a missing or malformed value fails with the key name). Each config record reads its own keys from it: `DatabaseConfig.from(settings)`, `AuthSettings.from(settings)`, `SchedulerSettings.from(settings)`. Anything absent keeps the record's default. Tests build `Settings.of(map)` / `Settings.fromClasspath("db.properties")` instead.

---

## 3. `platform/` (infrastructure)

```
platform/
├── module/                                                         
│   ├── ERPModule.java              The contract every business module implements (see §4.1).
│   ├── ServiceRegistry.java        Startup-time lookup of platform services + modules' public APIs.
│   │                               Register under an interface; freeze(); never passed below XxxModule.
│   ├── ModuleHost.java             Runs the module phases (entities, services, handlers, jobs, routes + web server) for Main AND tests;
│   │                               startScheduler() runs jobs (server mode only).
│   └── ServiceNotFoundException.java  Wiring bug at startup (hints at module order in AllModules).
│
├── db/                                                             
│   ├── UnitOfWork.java             One transaction on one pooled connection (autocommit off).
│   │                               commit()/rollback()/close(); close() without commit = rollback.
│   │                               withSavepoint(...) / runInSavepoint(...) for "skip the failures" batches.
│   ├── UnitOfWorkFactory.java      begin()/beginReadOnly() for manual control;
│   │                               execute()/run()/executeReadOnly() = commit-on-success, rollback-on-throw.
│   ├── DataSources.java            HikariCP pool from a DatabaseConfig (autocommit off, driver properties).
│   ├── DatabaseConfig.java         Plain record: jdbcUrl, credentials, pool settings, driver properties.
│   │                               of(...) = dev defaults; withPool/withDriverProperties. toString hides password.
│   ├── DatabaseMigrator.java       Flyway migrate(DatabaseConfig) from classpath:db/migration, run before the pool is opened.
│   └── DataAccessException.java    Unchecked SQLException wrapper; isRetryable() = deadlock / lock wait.
│
├── entity/                         Declarative entity metadata (see §5)                
│   ├── EntityDefinition.java       Table, fields, relations, company scope, soft delete, version, mapper.
│   ├── Field.java                  API name <-> column, type, validation, filterable/sortable,
│   │                               readOnly/internal, read/write permissions.
│   ├── FieldType.java              STRING, LONG, DECIMAL, BOOLEAN, DATE, INSTANT, enums:
│   │                               JDBC read/bind, JSON coercion, allowed operators.
│   ├── Operator.java               equals, notEquals, gt/gte/lt/lte, in, contains, startsWith, isNull.
│   ├── Relation.java               MANY_TO_ONE / ONE_TO_MANY, lazy target (no init cycles).
│   ├── FieldAccess.java            Per-request "may read / may write this field".
│   ├── EntityRegistry.java         All entity DEFs; freeze() validates every DEF and relation target.
│   └── EntityDefinitionScanner.java  TEST-ONLY: finds classes with a DEF constant (no startup scanning).
│
├── query/                          Safe client-driven SQL (see §5)                    
│   ├── QuerySpecParser.java        JSON body {where, sort, page} -> QuerySpec. Shape + limits only.
│   ├── QuerySpec.java, FilterNode.java   Parsed query (and/or/not/condition tree).
│   ├── QueryLimits.java            Caps: depth 4, 20 conditions, 3 sorts, page ≤ 200 (default 50), ...
│   ├── QueryTranslator.java        QuerySpec + EntityDefinition -> parameterized SELECT. Adds company
│   │                               and soft-delete predicates first; to-one = LEFT JOIN, to-many = EXISTS.
│   ├── QueryExecutor.java          Runs an SQLQuery, maps rows, returns entities or visible-field maps.
│   └── SQLQuery.java, SQLUpdate.java, Bind.java
│
├── error/                          HTTP-agnostic business failures                    
│   ├── ErpException.java           Base: stable UPPER_SNAKE_CASE code + client-safe message.
│   ├── ErrorCategory.java          NOT_FOUND, VALIDATION, CONFLICT, FORBIDDEN, UNAUTHENTICATED, RATE_LIMITED.
│   ├── ResourceNotFoundException, ValidationFailedException (+ FieldError list), ConflictException,
│   │   ForbiddenException, UnauthenticatedException, RateLimitedException
│   └── FieldError.java             (field, code, message) for per-field validation problems.
│
├── web/                                                                            
│   ├── WebServer.java              The only class that assembles Javalin. Installs, in order:
│   │                               JSON mapper -> RequestIdFilter -> auth filter -> ExceptionMapper -> routes.
│   ├── WebServerConfig.java        host + port record.
│   ├── Routes.java                 What a module sees: get/query/post/put/patch/delete. Nothing else.
│   ├── RequestIdFilter.java, RequestIds.java   X-Request-Id in/out, MDC "requestId".
│   ├── RequestContext.java         (requestId, userId, companyId, permissions). Passed explicitly.
│   ├── RequestContexts.java        Get/set it on the Javalin ctx; require(ctx) -> 401 if missing.
│   ├── ApiError.java               RFC 7807 problem+json body + code, requestId, errors.
│   ├── ErrorTranslator.java        Pure logic: exception -> status + ApiError, and what to log.
│   ├── ExceptionMapper.java        Thin Javalin adapter over ErrorTranslator (catch-all).
│   ├── JsonMapperFactory.java      Jackson rules: BigDecimal as string, ISO dates, reject unknown props,
│   │                               reject null primitives and fractional ints.
│   ├── JsonBodies.java             Read a body; any parse problem -> generic 400 (no input echo).
│   ├── EntityEndpoints.java        Generic QUERY / GET / POST / PATCH / DELETE routes from an EntityDefinition
│   │                               alone (§5.3). register(routes, path, DEF, permissionPrefix).
│   └── HealthChecks.java, HealthRoutes.java   GET /health/live, GET /health/ready (503 if a check fails).
│
├── security/                                                                        (no RBAC yet)
│   ├── SessionAuthenticator.java   Interface: token -> Optional<Principal>. Implemented by modules.users.
│   ├── AuthFilter.java             Bearer token -> RequestContext. No header = anonymous; bad token = 401.
│   ├── SessionTokens.java          256-bit random tokens; only SHA-256(token) is stored.
│   ├── PasswordHasher.java         PBKDF2-HMAC-SHA256, 600k iterations, self-describing format, needsRehash().
│   ├── LoginThrottle.java          In-memory lockout per user + IP (move to Redis if replicated).
│   ├── SystemActor.java            Interface: the SYSTEM user's id, for work nobody asked for in person
│   │                               (scheduled runs). Implemented by modules.users, created on first use.
│   └── PermissionChecker.java       SINGLE choke point for permission checks.
│
├── events/                                                                          (sync only)
│   ├── DomainEvent.java            Marker: companyId(), eventType(). Use immutable records, past tense.
│   ├── EventBus.java               subscribe() at startup, freeze(), publish(uow, event) synchronously
│   │                               in the caller's transaction. Supertype subscriptions; loop guard (depth 8).
│   ├── EventHandler.java           handle(uow, event).
│   └── outbox/                      OutboxEntry, OutboxRepository, OutboxDispatcher (async, retries).
│
├── scheduler/                       Recurring jobs, DB-driven and multi-instance safe (see §17)
│   ├── Scheduler.java              Registry of job types (register, freeze) + start() / stop().
│   ├── JobType.java, Job.java      What a module registers: key, description, the job, timeout,
│   │                               parameter rules, scope (SYSTEM / COMPANY / ANY), permission, default schedule.
│   ├── JobContext.java             What a running job sees: run, trigger, company, requester,
│   │                               RequestContext to act as, parameters, isCancelled(), setResultSummary().
│   ├── DefaultSchedule.java, ParameterValidator.java, JobCancelledException.java
│   ├── SchedulerSettings.java      scheduler.* (poll interval, workers, lease, timeouts, retention, instance id).
│   ├── RunStatus, TriggerType, MisfirePolicy   enums stored as-is in the tables.
│   ├── schedule/                   Pure, no DB: CronExpression (own 5-field parser), ScheduleSpec (CRON | INTERVAL,
│   │                               validation, next fire time, preview), ScheduleType.
│   └── internal/                   The engine; modules may not use it (ArchUnit): SchedulerEngine (poll loop),
│                                   JobRunner, RunRecorder, ScheduleRepository, RunRepository, PruneRunsJob, ...
│
├── audit/           AuditService (subscribes to events; who/what/when/old->new), AuditLogRepository
├── numbering/       DocumentNumberService (per company + doc type + fiscal year, gapless option)
├── settings/        SettingsService (company-scoped key/value with global defaults)
├── idempotency/     IdempotencyService (Idempotency-Key on document-creating POSTs)
└── files/           FileStorage interface, LocalFileStorage (S3-compatible later)
```

### 3.1 Resources owned by `platform`

```
platform/src/main/resources/
├── db.properties                 Pool + JDBC URL params (see §2.3).
└── db/
    ├── migration/                Flyway, versioned, never edit an applied migration.
    │   ├── V1__.sql              companies, currencies, units_of_measure, tax_groups, tax_rates, tax_group_lines
    │   ├── V2__create_users_and_products_tables.sql   users, user_credentials, product_categories, products
    │   ├── V3__create_user_sessions_table.sql         user_sessions
    │   ├── V4__multi_company_users_and_permissions.sql   memberships, roles, overrides (§16)
    │   └── V5__scheduler.sql                          scheduled_jobs, scheduled_job_runs (§17)
    └── old_schema.tmp.sql        Legacy MySQL dump kept as reference (git-ignored via *.tmp.*).
```

All migrations currently live in `platform`, including tables owned by business modules. That keeps one linear Flyway history; revisit only if modules ever need to ship independently.

### 3.2 Notes

- **Scheduler jobs are just orchestrators triggered by time.** A job calls an orchestrator like a controller would, with the `RequestContext` from its `JobContext` (SYSTEM, or the user who pressed "run now"). The scheduler opens no transaction around it: the orchestrator owns its own (`Job.transactional` for the one-transaction case). See §17.
- **Outbox writes happen inside the originating transaction**, so events are never lost or emitted for rolled-back work.
- **`ServiceRegistry` is a composition-root tool.** Only platform services and modules' public APIs go in. Repositories, operations, orchestrators and listeners never do; an `XxxModule` resolves what it needs once and passes plain constructor arguments down.

### 3.3 Services in the `ServiceRegistry`

| Type                    | Published by            |
| ----------------------- | ----------------------- |
| `UnitOfWorkFactory`     | ModuleHost              |
| `EventBus`              | ModuleHost              |
| `EntityRegistry`        | ModuleHost              |
| `EntityEndpoints`       | ModuleHost              |
| `PermissionRegistry`    | ModuleHost              |
| `PermissionChecker`     | ModuleHost              |
| `Scheduler`             | ModuleHost (job types registered in phase 4, run by `startScheduler`) |
| `SessionAuthenticator`  | `UsersModule`           |
| `CompanyAccess`         | `UsersModule` (wrapped in `CachedCompanyAccess`) |
| `UsersApi`              | `UsersModule`           |
| `SystemActor`           | `UsersModule`           |

### 3.4 Authentication model

Opaque, server-side sessions (not JWT), chosen so sessions can be revoked instantly:

- `POST /auth/login` verifies the password, creates a row in `user_sessions` holding `SHA-256(token)`, and returns the token once.
- Every request: `AuthFilter` -> `SessionAuthenticator.authenticate(token)` (an indexed lookup; `last_seen_at` touched at most once a minute).
- A session ends after `auth.session.idleTimeout` of inactivity or `auth.session.maxLifetime` after login, on logout, or when the password is changed.
- Failed logins are throttled per user + client IP (`auth.login.maxFailures` within `auth.login.lockout`).

---

## 4. `modules/` (business domains)

### 4.1 Module contract: `ERPModule`

Every module has one `XxxModule` class implementing `platform.module.ERPModule`, listed in `modules.AllModules.create(...)` (the ONE module list: Main, MySqlTestStack and ModuleEntitiesTest all use it). `platform.module.ModuleHost` drives every module through the same phases, in order:

```
0. registerPermissions(permissionRegistry)     -> permissionRegistry.freeze()
1. registerEntities(entityRegistry)            -> entityRegistry.freeze(declared)  (fails fast on a broken DEF
                                                  or a field permission no module declared)
   DatabaseMigrator.migrate(...)  ->  DataSources.create(...)  ->  UnitOfWorkFactory
2. registerServices(serviceRegistry)           -> serviceRegistry.freeze()
3. registerEventHandlers(eventBus, services)   -> eventBus.freeze()
4. registerJobs(scheduler, services)           -> scheduler.freeze(declared)  (fails on a job permission
                                                  no module declared; the platform's own jobs come first)
5. registerRoutes(routes, services)            (inside webServer.start)
   host.startScheduler(settings)               server mode only: jobs start running (CLI commands never run jobs)
```

Only `name()` and `registerEntities` are abstract; every other phase is a default no-op, so a module overrides just the phases it takes part in.

**Module order matters:** a module may only use what modules listed _before_ it registered (services, and entities it points relations at). `UsersModule` is first because most entities reference `User` through `created_by` / `updated_by`.

### 4.2 Standard module template

Copy this shape when adding a module.

```
modules/<module>/
├── <Module>Module.java              Implements ERPModule. The ONLY place that builds the module's internals.
├── <Module>Api.java                 (optional) Public interface other modules / app commands may call. Published
│                                    in the ServiceRegistry; implemented by an orchestrator.
├── <Thing>Controller.java           Web layer. Parses request (JsonBodies), RequestContexts.require(ctx),
│                                    calls orchestrator, writes response.
├── <Action>Service.java             ORCHESTRATOR (layer 1). Owns sequence, transaction boundary
│                                    (uowFactory.execute), event emission.
├── operations/                      SINGLE-OPERATION layer (layer 2). One class = one job.
│   └── <Verb><Noun>Operation.java   implements modules.Operation<I, O>; nested record Input.
├── <Thing>Repository.java           PERSISTENCE (layer 3). Hand-written SQL through platform.db.Jdbc. No business rules.
├── <Thing>.java                     Entity: `extends StandardEntity`, `DEF = Entities.define(...)`, and one
│                                    Field/Relation constant per column of its OWN (standard columns are inherited).
├── events/                           PUBLIC contract with other modules.
│   └── <Thing><PastTense>Event.java
└── dto/                             Request/response records for the web layer only.
```

Plain master data (no business rules beyond field declarations) skips the controller/service/repository layers entirely: one `EntityEndpoints.register(...)` line per entity (see `masterdata.items`, §4.5, and §5.3). An entity whose writes need business rules gets a hand-written controller + orchestrator, and registers only `Action.READ` generically.

### 4.3 Module list

```
modules/
├── Operation.java    interface Operation<I, O> { O execute(UnitOfWork uow, I input); }
├── common/           Entities.define: StandardEntity.define + createdByUser/updatedByUser relations to User
├── users/            users, credentials, sessions, login/logout/me, set-password, SYSTEM user,
│                     job users.purge-expired-sessions
├── masterdata/
│   ├── items/        products + product categories (read/query only so far)
│   ├── customers/   
│   └── vendors/     
├── company/          legal entities, fiscal calendars, currencies, exchange rates (tables exist in V1)
├── tax/              rates, groups, rules per jurisdiction, tax postings (tables exist in V1)
├── gl/               chart of accounts, journals, periods, reports
├── sales/            quotes, sales orders, fulfillment, invoicing
├── ar/               customer invoices, receipts, aging
├── ap/               vendor bills, payments, aging
├── inventory/        warehouses, locations, stock moves, costing (FIFO / weighted avg)
├── purchasing/       POs, goods receipts, 3-way match
├── fixedassets/      asset register, depreciation schedules, disposal
├── bank/             bank accounts, statements, reconciliation, payment runs
├── approvals/        generic approval workflows (states, approvers, thresholds)
└── system/
    └── scheduling/   scheduler admin API under /scheduler (schedules, run now, history; §17.5)
```

Roles and permissions will land in `users` (the module that already owns identity) rather than a separate `auth` module.

### 4.4 Worked example (real): `users`

```
modules/users/
├── UsersModule.java                 ERPModule. Builds repositories, operations and orchestrators.
│                                    Publishes SessionAuthenticator, CompanyAccess (cached) + UsersApi.
├── UsersApi.java                    PUBLIC API: setPassword, assignRole. Used by the CLI commands.
├── UsersApiService.java             Implements UsersApi by delegating to AuthService / AuthorizationService.
├── AuthController.java              POST /auth/login, POST /auth/logout, GET /auth/me
├── AuthService.java                 ORCHESTRATOR: login (two short transactions, slow hash between them,
│                                    so the pool isn't held during PBKDF2), logout, profile, setPassword.
├── SessionService.java              ORCHESTRATOR behind AuthFilter: implements SessionAuthenticator.
├── CompanyAccessService.java        ORCHESTRATOR behind AuthFilter: implements CompanyAccess
│                                    (default company, effective permissions). Runs on cache misses.
├── AuthorizationService.java        ORCHESTRATOR for access changes (assignRole); invalidates the cache
│                                    after commit.
├── CredentialVerifier.java          Pure helper (no DB): same error + same timing for every failure.
├── EffectivePermissions.java        Pure helper: the §16.5 formula.
├── AuthSettings.java                Session/login policy + permission cache TTL; AuthSettings.from(settings)
│                                    reads auth.*. (UsersModule wraps its Clock with Clock.tick(.., 1s): every
│                                    time it computes is whole seconds, like a MySQL TIMESTAMP.)
├── operations/
│   ├── FindLoginCandidateOperation.java
│   ├── CreateSessionOperation.java  also prunes the user's dead sessions
│   ├── ResolveSessionOperation.java touches last_seen_at at most once a minute
│   ├── RevokeSessionOperation.java
│   ├── SetPasswordOperation.java    replaces credential (keeps history) + revokes all sessions
│   ├── GetUserProfileOperation.java
│   ├── ListUserCompaniesOperation.java       accessible companies + the default rule (§16.7)
│   ├── LoadEffectivePermissionsOperation.java membership check + formula; warns once per stale name
│   └── AssignRoleOperation.java     adds/re-enables membership, sets default if none, adds role
├── UserAccountRepository.java       users + user_credentials
├── SessionRepository.java           user_sessions; every time bound from Java, never NOW() in SQL
├── AccessRepository.java            user_companies, roles, role_permissions, user_company_roles,
│                                    user_permission_overrides
├── User.java                        StandardEntity on a GLOBAL table (StandardEntity.defineGlobal); the
│                                    TARGET of createdByUser/updatedByUser, so it doesn't use Entities.define
└── dto/
    ├── LoginRequest.java            toString() hides the password
    ├── LoginResponse.java           toString() hides the token; lists companies + defaultCompanyId
    ├── MeResponse.java              user, the request's company, sorted permissions
    ├── CompanySummary.java
    └── UserProfile.java
```

Note: `CredentialVerifier` and `EffectivePermissions` show the pattern for logic shared by an orchestrator that must not touch the DB: a plain helper class, not an operation.

### 4.5 Worked example (real): `masterdata.items`

```
modules/masterdata/items/
├── ItemsModule.java                 ERPModule (name "masterdata.items"). No services, no handlers yet.
│                                    QUERY/POST /masterdata/products, GET/PATCH/DELETE .../{id}
│                                    the same on /masterdata/product-categories
├── Product.java                     DEF on `products`; relations category -> ProductCategory,
│                                    createdByUser / updatedByUser -> User
└── ProductCategory.java             DEF on `product_categories`
```

The whole module is entity declarations, `declareEntity(...)` permissions and two `EntityEndpoints.register(...)` lines: QUERY / GET / POST / PATCH / DELETE on both entities.

### 4.6 Target example: `sales` 

```
modules/sales/
├── SalesModule.java
├── SalesOrderController.java            POST /sales/orders/{id}/post
├── SalesOrderPostingService.java        ORCHESTRATOR
├── operations/
│   ├── MarkOrderAsPostedOperation.java
│   ├── GenerateOrderTransactionOperation.java
│   └── CreateItemEntriesOperation.java
├── SalesOrderRepository.java            PERSISTENCE
├── SalesOrder.java
├── SalesOrderLine.java
├── events/
│   └── SalesOrderPostedEvent.java       Other modules (GL, inventory) listen to this
└── dto/
    ├── CreateOrderRequest.java
    └── OrderResponse.java
```

### 4.7 Target example: `gl` 

```
modules/gl/
├── GlModule.java
├── GeneralLedgerApi.java                PUBLIC API for other modules (e.g. "get account balance")
├── JournalPostingService.java           ORCHESTRATOR: validate -> insert -> emit JournalPostedEvent
├── operations/
│   ├── ValidateJournalBalanceOperation.java   sum(debits) == sum(credits), else reject
│   ├── ValidatePeriodOpenOperation.java       period must be open for the entry date
│   └── InsertJournalEntryOperation.java
├── AccountRepository.java
├── JournalEntryRepository.java
├── PostingPeriodRepository.java
├── Account.java
├── JournalEntry.java                    Immutable once posted. Corrections = reversal + new entry.
├── JournalLine.java                     account, debit/credit, currency, optional dimensions
│                                        (cost center, department, project, location)
├── listeners/                           Where other modules' events become journal entries
│   ├── SalesOrderPostedGlListener.java
│   ├── VendorBillPostedGlListener.java
│   └── InventoryMoveGlListener.java
└── reports/                             Trial balance, GL detail, balance sheet, income statement,
                                         sub-ledger vs. control account reconciliation
```

Why listeners live in `gl` and not in `sales`: sales knows nothing about accounting. "How does an invoice become a journal entry" is an accounting rule, so it belongs with the GL.

---

## 5. Entity & query framework (`platform.entity` + `platform.query`)

Entities are described once, declaratively, and that description drives SQL generation, JSON output, validation and field-level permissions.

### 5.1 Declaring an entity

```java
public class Product extends StandardEntity {     // id, companyId, version, created/updated at/by, deletedAt
  // own fields + accessors only ...

  public static final EntityDefinition<Product> DEF = Entities.define(
      "product", "products", Product::new);        // API name, table, factory

  public static final Field<Product, String> NAME = DEF.string(
      "name", "name", Product::name, Product::setName)
      .filterable().sortable();

  public static final Relation<Product, ProductCategory> CATEGORY_REL = DEF.manyToOne(
      "category", () -> ProductCategory.DEF, "product_category_id");
}
```

Three ways to build a DEF, from most to least common:

| Builder | Declares | Use for |
| --- | --- | --- |
| `modules.common.Entities.define` | the 8 standard fields, soft delete on `deleted_at`, `updated_at` on patch, relations `createdByUser` / `updatedByUser` -> `User` | every business entity |
| `platform.entity.StandardEntity.define` | the same minus the two relations | `User` itself |
| `EntityDefinition.of` | nothing; declare everything by hand | tables not shaped like a business table (global tables, link tables) |

Conventions:

- The constant **must** be `public static final EntityDefinition<...> DEF`; `EntityRegistry.registerAll(Product.class, ...)` reads it.
- API names are camelCase, columns are snake_case; both are validated.
- Standard columns on business tables: `id`, `company_id`, `version`, `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted_at`. They come from `StandardEntity`; don't redeclare them.
- Field options: `required`, `filterable`, `sortable`, `readOnly`, `internal`, `maxLength`, `min/max`, `pattern`, read/write permissions. `Field.validate` reports through `platform.error.Validation`, so entity checks and hand-written checks use the same codes.

### 5.2 Querying

```
QUERY /masterdata/products
{ "where": { "and": [ { "field": "category.code", "op": "equals", "value": "PAINT" } ] },
  "sort":  [ { "field": "name", "dir": "asc" } ],
  "page":  { "limit": 50, "offset": 0 } }
-> { "data": [ {...} ], "page": { "limit": 50, "offset": 0 } }
```

Safety model (`QueryTranslator`): client text never reaches the SQL string (only identifiers from the DEF and `?` placeholders); the company and soft-delete predicates are added first and can't be removed; a field the caller can't read is "unknown" for filters and sorts; the query is parsed and translated **before** a connection is taken from the pool.

### 5.3 Writing (generic create / update / soft delete)

`platform.web.EntityEndpoints.register(routes, "/masterdata/products", Product.DEF, "masterdata.product")` gives:

```
QUERY  /masterdata/products              search                      .read     200 { data, page }
GET    /masterdata/products/{id}         one row                     .read     200 row | 404
POST   /masterdata/products              { field: value }            .create   201 row + Location
PATCH  /masterdata/products/{id}         { version, field: value }   .update   200 row | 404 | 409
DELETE /masterdata/products/{id}?version=N  soft delete              .delete   204 | 404 | 409
```

Permissions are `{prefix}.read/.create/.update/.delete`, declared with `PermissionRegistry.declareEntity(prefix, "products")`; `register(..., Action.READ)` limits the route set.

How a write runs (`platform.write`):

1. `WriteBuilder` (pure, before any connection): accepts only declared, non-internal fields the caller may read and write, and that the operation allows; coerces and validates each (`Field` options); reports every problem at once (422). Adds `company_id`, `created_at/by`, `updated_at/by` from the request and the server clock (`WriteContext`), never from the body. Updates and deletes are `... WHERE id = ? AND company_id = ? AND deleted_at IS NULL AND version = ?` with `version = version + 1`.
2. `WriteExecutor` (in the transaction): `unique()` fields are free in the company (soft-deleted rows count, like the database index) -> 422 `ALREADY_EXISTS`; a value written to a many-to-one relation's foreign key points at a live row of the SAME company -> 422 `INVALID_REFERENCE` (a client can't link to another company's data). 0 rows updated -> 404 `NOT_FOUND` or 409 `VERSION_CONFLICT`.
3. `EntityChanged.Created/Updated/Deleted` is published in the same transaction (entity, id, company, user, time, changed field names). Subscribe to `EntityChanged` for all of them (the future audit log).
4. The response is the row re-read as the caller sees it.

Field options for writes: `required()` (on create), `readOnly()` (server-set: audit columns, totals), `immutable()` (settable on create only), `unique()` (per company), plus `maxLength / min / max / pattern / writePermission`. `StandardEntity`'s audit and soft-delete fields are `readOnly()`.

Database constraint violations the checks can't see (a concurrent insert; a foreign key to a table without an entity, like `currencies`) still become clean errors: `ErrorTranslator` maps MySQL 1062 -> 409 `DUPLICATE_VALUE` and 1452 -> 422 `INVALID_REFERENCE`.

---

## 6. `shared/` 

Empty today. Planned contents:

```
shared/
├── Money.java        BigDecimal amount + currency code. Never use double/float for money.
├── DateRange.java
└── Result.java       Optional success/failure wrapper for expected (non-exceptional) outcomes.
```

Keep this package tiny. If a class has business meaning, it belongs in a module.

`shared` sits **below** `platform`, so it can only hold code with no dependency at all. Helpers that every module repeats but that touch platform types live elsewhere:

| Helper | Where | Replaces |
| --- | --- | --- |
| `StandardEntity` | `platform.entity` | 8 fields, 8 Field constants, 16 accessors per entity |
| `Entities.define` | `modules.common` | the createdByUser / updatedByUser relations per entity |
| `Jdbc.update / queryFirst / queryList` | `platform.db` | prepare / bind / catch SQLException per repository method |
| `Timestamps` | `platform.db` | Instant <-> TIMESTAMP (also used by `FieldType.INSTANT`) |
| `Validation` | `platform.error` | hand-built `List<FieldError>` + throw |

---

## 7. Layer rules

| Layer            | Owns                                                                        | May call                                                          | Must NOT                                                   |
| ---------------- | --------------------------------------------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------- |
| **XxxModule**    | Building the module's internals, publishing its API, registering routes     | `ServiceRegistry` (startup only)                                  | Contain business logic; pass the registry deeper           |
| **Controller**   | HTTP parsing, `RequestContexts.require`, permission check, response mapping | Orchestrator                                                      | Contain business logic; touch repositories                 |
| **Orchestrator** | Sequence of steps, transaction boundary (`uowFactory`), event emission      | Operations, pure helpers, EventBus, other modules' public APIs    | Run SQL; contain step-level rules                          |
| **Operation**    | ONE unit of business logic                                                  | Repositories (its own module's), pure helpers                     | Call other operations; publish events; manage transactions |
| **Repository**   | SQL <-> domain mapping                                                      | `uow.connection()`                                                | Contain business rules; call anything but JDBC             |
| **Listener**     | Turn an event into local work                                               | Its own module's orchestrator/operations                          | Modify the source module's data                            |

If an operation "needs" another operation, that is a sign the orchestrator should call both, or the shared logic should be extracted to a helper both operations use.

Input checks: `Validation.check().required(..).maxLength(..).throwIfInvalid()`: standard codes, every field's problem in one response, at most one error per field.

Errors: throw a subclass of the matching category class (`ConflictException`, `ValidationFailedException`, ...) with a stable code, e.g. `GL_PERIOD_CLOSED`. Never put SQL, stack details or other users' data in the message.

---

## 8. Code skeletons

### 8.1 Operation interface (`modules/Operation.java`)

```java
public interface Operation<I, O> {
    O execute(UnitOfWork uow, I input);
}
```

Convention from `users`: the input is a nested `record Input(...)` on the operation class; override `toString()` on any record that holds a secret.

### 8.2 Orchestrator (owns the transaction)

```java
public class SalesOrderPostingService {
    private final UnitOfWorkFactory uowFactory;
    private final MarkOrderAsPostedOperation markPosted;
    private final GenerateOrderTransactionOperation generateTransaction;
    private final CreateItemEntriesOperation createItemEntries;
    private final EventBus eventBus;

    public SalesOrder post(long orderId, RequestContext ctx) {
        return uowFactory.execute(uow -> {
            SalesOrder order = markPosted.execute(uow, new MarkOrderAsPostedOperation.Input(orderId, ctx));
            var tx = generateTransaction.execute(uow, new GenerateOrderTransactionOperation.Input(order));
            createItemEntries.execute(uow, new CreateItemEntriesOperation.Input(order, tx));

            eventBus.publish(uow, new SalesOrderPostedEvent(order.id(), order.companyId(), tx.id()));
            return order;
        });
    }
}
```

### 8.3 Operation

```java
public class MarkOrderAsPostedOperation
        implements Operation<MarkOrderAsPostedOperation.Input, SalesOrder> {

    public record Input(long orderId, RequestContext ctx) {}

    private final SalesOrderRepository repository;

    public SalesOrder execute(UnitOfWork uow, Input in) {
        SalesOrder order = repository.findById(uow, in.ctx().companyId(), in.orderId())
            .orElseThrow(() -> new ResourceNotFoundException("SALES_ORDER_NOT_FOUND", "Sales order not found"));
        if (order.status() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException(order.id(), order.status()); // extends ConflictException
        }
        order.markPosted();
        repository.update(uow, order);
        return order;
    }
}
```

### 8.4 Repository

```java
public class SalesOrderRepository {
    public Optional<SalesOrder> findById(UnitOfWork uow, long companyId, long id) {
        return Jdbc.queryFirst(uow,
            "SELECT ... FROM sales_orders WHERE company_id = ? AND id = ? AND deleted_at IS NULL",
            rs -> new SalesOrder(rs.getLong("id"), ..., Timestamps.read(rs, "posted_at")),
            companyId, id);
    }

    public int markPosted(UnitOfWork uow, long id, long version, Instant now) {
        return Jdbc.update(uow, "UPDATE sales_orders SET status = ?, posted_at = ?, version = version + 1"
            + " WHERE id = ? AND version = ?", OrderStatus.POSTED, now, id, version);
    }
}
```

`Jdbc` binds parameters in order by type (Instant through `Timestamps` in UTC, enums by name, the rest via `setObject`) and wraps `SQLException` in `DataAccessException`. Drop to `uow.connection()` only for what it can't do (batches, generated keys, streaming).

Bind times from Java (`Instant`), never `NOW()` in SQL, so rules don't depend on the MySQL session time zone. Optimistic locking: `UPDATE ... WHERE id = ? AND version = ?`, and 0 rows updated = conflict (`WriteExecutor` already does this generically).

### 8.5 Request lifecycle (Javalin)

```
before  -> RequestIdFilter (X-Request-Id, MDC)
        -> AuthFilter: Bearer token -> SessionAuthenticator -> caller (or anonymous / 401)
handler -> Controller: RequestContexts.require(ctx)   resolves the company (X-Company-Id / default) and
                                                      permissions via CompanyAccess, once per request
           PermissionChecker.require(rc, "x.y.z")    403 PERMISSION_DENIED
           JsonBodies.read(...), calls orchestrator
           Orchestrator: uowFactory.execute(uow -> ...)   commit on return, rollback on throw,
                                                          connection back to Hikari either way
error   -> ExceptionMapper -> ErrorTranslator -> ApiError (RFC 7807, application/problem+json)
after   -> RequestIdFilter clears the MDC
```

Use savepoints (`uow.withSavepoint(...)` / `uow.runInSavepoint(...)`) for "process N items, skip the failures" batches, so one failure doesn't abort the whole transaction.

---

## 9. Event flow: sales order to GL 

The `EventBus` is built; no module publishes events yet. Target flow:

```
Controller
  -> SalesOrderPostingService (orchestrator)
       -> operations (mark posted, create transaction, item entries)
       -> EventBus.publish(uow, SalesOrderPostedEvent)     [same DB transaction]
            -> SalesOrderPostedGlListener (gl module)
                 -> builds balanced JournalEntry
                 -> JournalPostingService (validate balance, validate period, insert)
            -> InventoryListener (inventory module), etc.
  <- commit: order + transaction + item entries + journal entry all succeed, or none do
```

Two event tiers:

| Tier                            | When                                                            | Mechanism                                                                                        |
| ------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Synchronous, in-transaction** | Financial postings, stock updates, anything that must be atomic |  `EventBus` handlers run inside the caller's UoW                                               |
| **Async via outbox**            | Emails, webhooks, search index, notifications                   |  Row written to `outbox` in the same transaction; `OutboxDispatcher` processes later with retries |

---

## 10. Testing

| Kind                         | Where                                      | How                                                                                      |
| ---------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------- |
| Pure unit                    | `platform/.../security`, `query`, `entity` | `PasswordHasherTest`, `SessionTokensTest`, `LoginThrottleTest` (manual clock), `QueryTranslatorTest`, `FieldValidationTest` |
| Web plumbing                 | `platform/.../web/WebServerTest`, `AuthFilterTest` | Real Jetty on a random port; `DevAuthFilter` (test sources only) fakes identities; `FakeDataSource` fakes JDBC |
| Wiring                       | `modules/.../ModuleEntitiesTest`           | Every module's entities register and freeze; scanner proves no DEF was forgotten         |
| End-to-end against MySQL     | `AuthRoutesMySqlTest`, `ItemRoutesMySqlTest` | `MySqlTestStack`: full server started like Main (AllModules + ModuleHost), Flyway clean + migrate on `TEST_DATABASE_NAME` (must end in `_test`), seeded data, real logins. Skipped if the DB is unavailable |
| Scheduler, pure              | `platform/.../scheduler`                   | `CronExpressionTest` (syntax, month ends, leap years, DST gap and overlap in America/Toronto), `ScheduleSpecTest`, `SchedulesTest`, `RetryPolicyTest`, `SchedulerRegistryTest` |
| Scheduler against MySQL      | `modules/.../scheduler`, `system/scheduling` | `SchedulerStoreMySqlTest` (row locks, leases, duplicate-fire guard, SYSTEM user), `SchedulerEngineMySqlTest` (the running engine with test jobs: retries, timeouts, misfires, crash recovery, manual runs, two instances, shutdown), `BuiltInJobsMySqlTest`, `SchedulingRoutesMySqlTest` (admin API) |
| Architecture                 |                                          | ArchUnit rules (§11)                                                                     |

The MySQL tests use a **local** MySQL configured through `.env`, not Testcontainers (the BOM is declared for a later switch).

---

## 11. Enforcing boundaries (ArchUnit) 

Maven enforces `app -> modules -> platform -> shared`. `modules/src/test/.../ArchitectureTest.java` enforces what lives together inside the `modules` artifact, on every build.

A **business module** is the package of an `ERPModule` implementation plus its subpackages (`users`, `masterdata.items`); new modules are picked up automatically. Packages outside every module (`common`, the root) are shared code; `AllModules` is the composition root and exempt.

| Rule | Why |
| --- | --- |
| A module uses another only through its `*Api` interfaces (in the module's root package), its `events` package, and its entities (classes with a `DEF`) | Services, operations, repositories and DTOs stay private to their module |
| No cycles between modules | Module order in `AllModules` is a dependency order |
| Operations never use another operation | The orchestrator calls both, or shared logic moves to a helper |
| Operations don't use `EventBus` or `UnitOfWorkFactory` | The orchestrator owns the transaction and the events |
| Operations are used only by orchestrators (`*Service`) and the module class | Controllers go through orchestrators |
| Repository classes are used only by operations (and built by the module class) | Only operations reach the database. Records nested in a repository are result shapes and may flow up |
| Only repositories use `java.sql`, `Jdbc` or `uow.connection()` | SQL lives in one layer |
| Only `ERPModule` classes see `ServiceRegistry` | The registry never travels deeper than the module class |
| Only `*Controller` classes depend on Javalin | Business code stays HTTP-free |
| Nothing in `modules` uses `platform.scheduler.internal` | The engine and its SQL can change freely; modules use `Scheduler`, `JobType`, `JobContext` and `schedule/` |
| `events` packages hold records implementing `DomainEvent` | Events are a public, immutable contract |

The rules rely on the naming conventions of §4.2 (`*Module`, `*Api`, `*Controller`, `*Service`, `*Repository`, `operations/`, `events/`). A class nested in another (a local record, a lambda) belongs to its outer class's layer. Each rule was checked against deliberate violations when it was written.

Only consider JPMS (`module-info.java`) if you have a strong reason; it adds friction with reflection-heavy libraries like Jackson.

---

## 12. Cross-cutting checklist

Decide these early; they are painful to retrofit.

- [x] **Multi-company:** every business table has `company_id`; `RequestContext` carries it; `QueryTranslator`/`WriteBuilder` always filter on it, and `WriteExecutor` rejects references to another company's rows. Hand-written repositories must do the same.
- [ ] **Money:** `BigDecimal` + currency code everywhere; define rounding rules once (in `shared.Money`). JSON side done (BigDecimal serialized as string).
- [x] **Soft deletes** on master data (`deleted_at`, honored by the entity framework).
- [ ] **Audit trail** via events -> `audit_log`. Generic writes publish `EntityChanged`  and fill `created_by` / `updated_by` ; the `audit_log` listener is still to do.
- [x] **RBAC** through `PermissionChecker` only: roles + per-user GRANT/DENY overrides, per company (§16).
- [ ] **Document numbering** per company / doc type / fiscal year, gapless where legally required.
- [ ] **Idempotency keys** on POSTs that create financial documents.
- [x] **Optimistic locking** (`version` column) on mutable entities; enforced by `WriteBuilder` / `WriteExecutor` (version in the PATCH body, `?version=` on DELETE).
- [x] **Error format:** RFC 7807 everywhere (`ApiError` via `ExceptionMapper`).
- [ ] **Observability:** request id in MDC and printed on every log line (logback) ; basic metrics (Micrometer) still to do.
- [x] **Migrations:** Flyway, versioned, run at startup. Never edit an applied migration.
- [x] **Tests:** unit + real-MySQL end-to-end + ArchUnit module/layer rules. (Testcontainers not needed: the MySQL tests use a local `_test` database.)

---

## 13. GL essentials (quick reference)

- Every journal entry: `sum(debits) == sum(credits)`. Enforce at creation.
- Posted entries are **immutable**. Fix mistakes with a reversing entry + a new correct entry.
- **Posting periods** are open/closed; posting into a closed period is rejected.
- **Sub-ledgers** (AR, AP, inventory) reconcile to a GL **control account**. Build the reconciliation report early.
- Journal lines support optional **dimensions** (cost center, department, project, location) from the start.
- Core reports: trial balance, GL detail, balance sheet, income statement, sub-ledger reconciliation.

---

## 14. Build order and progress

1.  `platform/db` (UnitOfWork, Hikari), `platform/web` (Javalin, errors, RequestContext), Flyway baseline.
2.  `platform/security` + `modules/users` (sessions, login throttle, set-password CLI), multi-company users + RBAC (§16).  Admin API for roles / overrides / memberships (with the generic write side).
3.  `platform/events` (sync bus ),  `platform/audit`, `platform/numbering`, `platform/settings`.
4.  `modules/masterdata/items` (query only) ;  `modules/company`, `masterdata/customers`, `masterdata/vendors`, `modules/tax`.
5.  `modules/gl` (accounts, periods, journal posting, trial balance).
6.  First transactional module (e.g. `sales` or `ap`) wired to GL through events, as the reference implementation for all later modules.
7.  `platform/scheduler` (done 2026-11-23, §17) + outbox dispatcher (to do; it can be a scheduler job or its own loop).
8.  Remaining modules (inventory, purchasing, bank, fixed assets, approvals).
9.  ArchUnit rules (§11).

---

## 15. Known gaps & loose ends

Open:

- **`V1__.sql` has no description.** Leave it: Flyway validates descriptions, so renaming an applied migration fails validation on every existing database. Just give future migrations a description.
- **Stubs:** `shared` has no classes.
- **Scheduler loose ends:** see §17.9.
- **Unused but planned dependencies:** Lettuce (`redis.port` unused; no longer needed by the scheduler), Testcontainers BOM (no module depends on it; the MySQL tests use the local database).

Fixed on 2026-11-18:

- `Main` now reads `server.port` (8080) instead of hard-coding 8081.
- `db.properties` pool and driver settings now take effect (pool max 6, 30s connection timeout, 2s leak detection); keys grouped under `database.pool.*`.
- The shutdown hook stops the web server, then closes the Hikari pool.
- Dead commented-out code removed from `Main`; missing DB env vars now fail with a clear "Missing required environment variable" message.
- `ItemService` renamed `ItemsModule`.
- jjwt removed (sessions are opaque tokens, not JWT).

---

## 16. Multi-company users & permissions 

_Status: built 2026-11-22 (migration V4), steps 1-5 of §16.13; step 6 (admin API) comes with the generic write side. Where the code differs from the original design below, this note wins:_

- _The company is resolved **lazily**: AuthFilter only validates the session; the first `RequestContexts.require(ctx)` picks the company and loads permissions. Routes that need no company (`/auth/logout`) call `RequestContexts.requireUserId(ctx)` and work for a multi-company user without a default._
- _`PermissionSource` + `PermissionCache` became one interface, `platform.security.CompanyAccess` (`permissions(user, company)`, `defaultCompany(user)`), implemented by `CompanyAccessService` and wrapped in the `CachedCompanyAccess` decorator (TTL `auth.permissions.cacheTtl`, default PT5M). `Principal` is just `(userId)`._
- _`PermissionRegistry.entity(def, prefix)` isn't built: modules `declare(...)` each permission they actually check. CRUD permissions arrive with the write side._
- _`PermissionChecker.require` fails on an undeclared permission (a typo would otherwise deny everyone); `EntityEndpoints` checks that at route registration._
- _`/auth/login` returns `companies` + `defaultCompanyId` so the client can pick; `/auth/me` returns the request's company and its sorted permissions. 400s use the new `BadRequestException` (`ErrorCategory.BAD_REQUEST`)._
- _`assign-role` re-enables a disabled membership. Run from the CLI (another process), it reaches a running server within the cache TTL._

### 16.1 Decisions this design rests on

- **Single instance**, on premise. No Redis needed: permissions are cached in memory, sessions stay in MySQL. Both sit behind interfaces so Redis can be added later as one more implementation.
- **One user account, member of several companies** (option 2). Roles and per-user overrides are granted **per company**.
- **Method-level and field-level permissions only.** No record-level rules: the company filter stays the only mandatory row condition.
- **Permissions are declared in code**, so the code is the single source of truth and an upgrade that adds permissions is visible.
- Customization ships as a release; no runtime-defined permissions.

### 16.2 Concepts

| Concept             | Scope           | Meaning                                                                                       |
| ------------------- | --------------- | --------------------------------------------------------------------------------------------- |
| **User**            | Install-wide    | One identity: `user_no`, name, password. No longer belongs to a single company.               |
| **Membership**      | User × company  | The user may work in this company. Can be disabled without touching the user or other companies. |
| **Permission**      | Install-wide    | A name declared in code, e.g. `masterdata.product.read`.                                      |
| **Role**            | Install-wide    | A named set of permissions (`ACCOUNTANT`, `CLERK`), defined once and reused in every company. |
| **Role assignment** | User × company  | "Marie is ACCOUNTANT in company 1 and CLERK in company 2."                                    |
| **Override**        | User × company  | One permission explicitly GRANTed or DENIed to one user in one company.                       |

Roles are install-wide on purpose: in a holding company the same "Accountant" job means the same thing in every subsidiary, and admins define it once. If company-specific roles are ever needed, add a nullable `company_id` to `roles` later; nothing below changes.

### 16.3 Permission names

Lowercase dot-separated segments, camelCase within a segment, validated by regex `[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*){2,}`:

```
<module>.<resource>.<action>                    methods / operations
<module>.<resource>.field.<field>.read|write    fields (only for sensitive fields)

masterdata.product.read          list / query products
masterdata.product.create
masterdata.product.update        PATCH products
masterdata.product.delete
masterdata.product.field.unitAmount.read     e.g. hide cost/price from some roles
sales.order.post                 a business action, not CRUD
users.role.manage
users.membership.manage
```

Rules:

- **Fields are open by default.** A field with no declared permission is visible/writable to anyone who holds the entity-level permission. Field permissions are the exception, for sensitive data (cost, salary, margins).
- Field read ≠ field write: `write` does not imply `read`; declare both when a field is restricted.
- **Never reuse or rename a permission name casually.** Grants are stored by name; a rename silently revokes it everywhere. Renames go through a Flyway data migration that rewrites stored grants.

### 16.4 How a module declares permissions

New registration phase on `ERPModule`, run **first** (before entities, so `EntityRegistry.freeze()` can check that every field permission is declared):

```java
public interface ERPModule {
  default void registerPermissions(PermissionRegistry permissions) {}   // NEW, phase 0
  void registerEntities(EntityRegistry entityRegistry);
  ...
}

// in ItemsModule
@Override
public void registerPermissions(PermissionRegistry p) {
  p.entity(Product.DEF, "masterdata.product");           // declares .read .create .update .delete
  p.entity(ProductCategory.DEF, "masterdata.productCategory");
  p.declare("masterdata.product.field.unitAmount.read", "See product unit amounts");
}

// in Product
public static final Field<Product, BigDecimal> UNIT_AMOUNT = DEF.decimal(...)
    .readPermission("masterdata.product.field.unitAmount.read");

// in registerRoutes
routes.query("/masterdata/products", queries.handler(Product.DEF, "masterdata.product.read"));
```

`PermissionRegistry` follows the same lifecycle as the other registries: register at startup, `freeze()`, then immutable. Each permission has a name, a human description (for a future admin screen) and the declaring module.

### 16.5 Effective permissions

For a given (user, company):

```
if membership missing or disabled, or company disabled  ->  no access to that company (403)

granted = ⋃ permissions of the user's enabled roles in this company
        ∪ (all registered permissions, if any of those roles has all_permissions)
        ∪ user GRANT overrides in this company
effective = granted − user DENY overrides in this company
effective = effective ∩ registered permissions        (stale names from old releases are ignored)
```

- **DENY beats everything**, including `all_permissions`. Simple to explain, and it lets you lock one sensitive action away from one admin.
- `all_permissions` is a flag on the role, not a list. An administrator role then automatically gets permissions added by future upgrades, without a data migration.
- Stored grants referencing a permission that no longer exists are ignored at runtime and **logged once at startup** (warn), so leftovers from a removed feature are visible but harmless.

### 16.6 Schema (migration V4)

```sql
-- Users become install-wide.
users:                 DROP company_id;  ADD default_company_id BIGINT UNSIGNED NULL  (FK companies)
user_credentials:      DROP company_id

user_companies (                                   -- membership
  user_id, company_id, is_enabled, created_at, created_by,
  PRIMARY KEY (user_id, company_id)
)

roles (
  id, code VARCHAR(50) UNIQUE, name, description,
  all_permissions BOOLEAN NOT NULL DEFAULT FALSE,
  is_enabled, version, created_at/by, updated_at/by, deleted_at
)

role_permissions (
  role_id, permission VARCHAR(150),
  PRIMARY KEY (role_id, permission)
)

user_company_roles (
  user_id, company_id, role_id, created_at, created_by,
  PRIMARY KEY (user_id, company_id, role_id),
  FOREIGN KEY (user_id, company_id) REFERENCES user_companies (user_id, company_id)
)

user_permission_overrides (
  user_id, company_id, permission VARCHAR(150),
  effect VARCHAR(5) NOT NULL CHECK (effect IN ('GRANT','DENY')),
  created_at, created_by,
  PRIMARY KEY (user_id, company_id, permission),
  FOREIGN KEY (user_id, company_id) REFERENCES user_companies (user_id, company_id)
)

user_sessions:         DROP company_id    (see 16.7: the company is chosen per request)
```

Data migration in the same script: one `user_companies` row per existing user from `users.company_id`, `default_company_id` = that company, and seed one role `ADMINISTRATOR` with `all_permissions = TRUE` (assigned to nobody; see 16.10 for bootstrapping).

Permission names are stored as strings, not as foreign keys to a `permissions` table: the code is the source of truth, and a table synced from it would just be a second copy to keep consistent.

**Related fix in the same migration:** `product_categories.code` and `products.product_no` are `UNIQUE` across the whole install, so two companies can't both have category `PAINT`. Make them `UNIQUE (company_id, code)` / `UNIQUE (company_id, product_no)`. Rule for all future company-scoped tables: business codes are unique **per company**. `users.user_no` stays install-wide unique (it is the login name).

**Entity framework impact:** `User.DEF` becomes `.globalTable()`. Otherwise the `createdByUser` LEFT JOIN adds `u.company_id = ?` and comes back empty for anyone whose home company isn't the current one.

### 16.7 Choosing the company: per request, not per session

**Recommended:** the session identifies only the _user_. The client says which company each request is for with an `X-Company-Id` header:

- Header present: must be a company the user is an enabled member of, else 403 `COMPANY_ACCESS_DENIED`.
- Header absent: the user's `default_company_id` (if still a valid membership), else their only membership; if they have several and no valid default, 400 `COMPANY_REQUIRED`.
- Single-company users never need to send the header; the frontend for multi-company users always sends it.

Why per request instead of a "switch company" endpoint that changes the session: two browser tabs share one session token. With session-bound company, switching in one tab silently changes the company the other tab writes to. Per-request selection makes "compare company 1 and company 2 side by side" safe, and keeps the session table simpler.

_Alternative, if you prefer:_ keep `user_sessions.company_id` as the active company plus `POST /auth/company` to switch. Less work for the client, but has the multi-tab problem above.

### 16.8 Request flow

```
RequestIdFilter
AuthFilter
  1. Bearer token -> SessionAuthenticator.authenticate(token) -> Principal(userId, defaultCompanyId)
  2. company = X-Company-Id header, else default rules (16.7)
  3. permissions = PermissionCache.get(userId, company)            in memory
        miss -> PermissionSource.load(userId, company)             users module, one query batch
        not a member -> 403 COMPANY_ACCESS_DENIED
  4. RequestContexts.set(ctx, new RequestContext(requestId, userId, company, permissions))
Controller / EntityEndpoints
  PermissionChecker.require(rc, "masterdata.product.read")         403 PERMISSION_DENIED if missing
  FieldAccess.forPermissions(rc::hasPermission)                    already wired in EntityEndpoints
```

`/auth/login`, `/auth/logout` and `/health/*` stay company-independent.

### 16.9 Code layout

```
platform/security/
├── Permission.java                record(name, description, module)
├── PermissionRegistry.java        declare(...), entity(def, prefix), freeze(), contains(), all()
├── PermissionChecker.java         require(rc, permission) / requireAny(...) / has(...)
│                                  THE single choke point. Throws ForbiddenException("PERMISSION_DENIED").
├── PermissionSource.java          interface: Optional<Set<String>> load(userId, companyId)
│                                  (empty = not an enabled member). Implemented by modules.users.
├── PermissionCache.java           interface: get / invalidate(user, company) / invalidateUser / invalidateAll
├── InMemoryPermissionCache.java   ConcurrentHashMap + TTL (default 5 min) as a safety net
├── AuthFilter.java                (changed) resolves company + permissions, see 16.8
└── SessionAuthenticator.java      (changed) Principal(userId, defaultCompanyId), no companyId/permissions

modules/users/
├── UsersModule.java               (changed) registerPermissions: users.* ; publishes PermissionSource
├── AuthorizationRepository.java   memberships, role grants, overrides for (user, company)
├── RoleRepository.java
├── operations/
│   ├── LoadEffectivePermissionsOperation.java   applies the 16.5 formula
│   ├── AssignRoleOperation.java, RevokeRoleOperation.java
│   ├── SetPermissionOverrideOperation.java
│   └── AddMembershipOperation.java, DisableMembershipOperation.java
├── AuthorizationService.java      ORCHESTRATOR for the admin actions; invalidates the cache after commit
└── dto/MeResponse.java            (changed) user, active company, memberships, permissions
```

`Main` gets one new phase (permissions before entities), builds `PermissionRegistry` and `InMemoryPermissionCache`, and publishes both plus `PermissionChecker` in the `ServiceRegistry`.

**Cache invalidation:** every admin change goes through `AuthorizationService`, which invalidates the affected (user, company) entries — or everything, for a role change — **after** the transaction commits. Single instance, so that is exact. The TTL only covers changes made directly in SQL (support work); `invalidateAll()` can also be exposed as a CLI command.

### 16.10 Bootstrapping the first administrator

Same approach as `set-password`, a CLI command on the server jar:

```
java -jar stempy.jar assign-role USER_NO COMPANY_CODE ROLE_CODE
```

Adds the membership if missing, then the role. Run once on a fresh install with `ADMINISTRATOR`; everything after that can go through the API.

### 16.11 Error codes

| Situation                                        | Status | Code                     |
| ------------------------------------------------ | ------ | ------------------------ |
| Missing permission                               | 403    | `PERMISSION_DENIED`      |
| `X-Company-Id` not an enabled membership         | 403    | `COMPANY_ACCESS_DENIED`  |
| Several memberships, no header, no valid default | 400    | `COMPANY_REQUIRED`       |
| `X-Company-Id` not a number                      | 400    | `INVALID_COMPANY_ID`     |

`PERMISSION_DENIED` names the missing permission in the message, which helps admins fix role setups; permission names aren't sensitive.

At login, a user with **no** enabled membership gets the usual invalid-credentials error, so the response doesn't reveal that the password was right.

### 16.12 Tests

- Unit: `PermissionRegistry` (name validation, duplicates, freeze), the 16.5 formula (role union, `all_permissions`, DENY wins, stale names dropped), `InMemoryPermissionCache` (TTL with a manual clock, invalidation), company resolution rules (16.7).
- `EntityRegistry.freeze()` rejects a field whose read/write permission isn't declared.
- `ModuleEntitiesTest`: every module's permissions register cleanly (ModuleHost does it before entities).
- MySQL end-to-end: a user in two companies with different roles sees different fields from the same query; DENY override; `X-Company-Id` for a non-member → 403; role change takes effect on the next request (cache invalidation); `createdByUser` resolves across companies.

### 16.13 Implementation order

1. `PermissionRegistry` + `registerPermissions` phase + field-permission validation in `EntityRegistry.freeze()`.
2. V4 migration (schema, data migration, unique-key fix) and repository changes for global users.
3. `PermissionSource` + `LoadEffectivePermissionsOperation` + `InMemoryPermissionCache`.
4. `AuthFilter` company resolution + `PermissionChecker`; protect the item routes and `/auth/me`.
5. `assign-role` CLI.
6. Admin operations (`AuthorizationService`) and their routes.

### 16.14 Out of scope

Record-level rules, company-specific roles, role inheritance/hierarchies, time-limited grants, Redis. Each can be added later without changing the model above.

---

## 17. Scheduler

_Status: built 2026-11-23 (migration V5). How to call the admin API: docs/API_GUIDE.md §7._

Runs recurring jobs ("every 15 minutes", "daily at 07:00", "every Tuesday", "the first Monday of the month"). **Code says what, the database says when:** a module registers **job types** in code; rows of `scheduled_jobs` (**schedules**) say which job runs when, for which company, with which parameters. Each execution is a **run** in `scheduled_job_runs`.

### 17.1 Decisions this design rests on

- **The table is the source of truth, read on every poll.** There is no in-memory copy to reload: an edit through the API or with plain SQL applies within one poll (5 s). Timing changes are detected by a fingerprint (§17.3), so SQL edits need no extra step.
- **Several instances may share the database; no Redis.** Claiming a due schedule is a short transaction with `SELECT ... FOR UPDATE SKIP LOCKED` that also takes a **lease** (`locked_by`, `locked_until`). The lease is renewed while the job runs and taken over after a crash. MySQL is already a hard dependency, so a second coordination system would add a failure mode without adding a guarantee. The planned `DistributedLock (Redis)` was dropped.
- **The database clock decides "now"** (`UTC_TIMESTAMP()`, read once per transaction and bound as a parameter), so instances with drifting clocks still agree on what is due and when a lease expires.
- **An own cron parser** (`schedule/CronExpression`), not Quartz or cron-utils: a learning goal, no dependency, and the scheduler needs only next-fire-time computation. It supports standard 5-field cron plus `L`, `MON#1`, `5L` and the `@daily`-style shortcuts. It is tested against month ends, leap years and both DST transitions.
- **CRON and INTERVAL.** A CRON is evaluated in the schedule's IANA time zone (07:00 stays 07:00 local across DST). An INTERVAL is a fixed rate counted from `start_at` (else `created_at`), so it does not drift and does not depend on how long a run takes.
- **Missed fires are not replayed.** After downtime a schedule runs once (`FIRE_ONCE`) or records a `SKIPPED` run (`SKIP`), then continues from now.
- **A job is an entry point like a controller.** It calls an orchestrator with the `RequestContext` its `JobContext` provides. The scheduler opens no transaction around it, because a long job may want to commit in batches (`Job.transactional` covers the one-transaction case).
- **Who a run acts as.** A scheduled run acts as the **SYSTEM** user with every declared permission. A manual run ("run now") acts as **the user who asked**: in a company with their own permissions there (checked again when the run starts), so `created_by` and later the audit log name them.
- **SYSTEM is created at startup, not by a migration**, as a disabled user with no credentials, created by `SystemActor` on first use (safe with concurrent instances). A migration would take `users.id = 1` on a fresh database, which the README's first-user SQL and the MySQL test seeds use. `set-password SYSTEM` is refused.
- **Job types guard who may schedule them.** Because scheduled runs have every permission, a job type can name a `permission` that is required to create, change, delete or run its schedules. Its `scope` (SYSTEM, COMPANY or ANY) also limits where schedules may live: housekeeping that spans companies is SYSTEM-only. A permission no module declares fails startup.

### 17.2 Concepts

| Term | What | Where |
| --- | --- | --- |
| Job type | Key (`users.purge-expired-sessions`), description, the `Job`, timeout, parameter rules, scope, permission, optional default schedule | Code: `ERPModule.registerJobs` |
| Schedule | Job key + when (CRON / INTERVAL + time zone, window, enabled) + for whom (company or install-wide) + parameters + failure policy | `scheduled_jobs` row |
| Run | One execution or skipped fire: trigger, attempt, status, timings, instance, outcome | `scheduled_job_runs` row |
| Trigger | `SCHEDULE` (a fire time), `RETRY` (after a failure), `MANUAL` ("run now", `requested_by` set) | |
| Status | `QUEUED` (manual, waiting) -> `RUNNING` -> `SUCCEEDED` / `FAILED` / `TIMED_OUT` / `ABANDONED`; or `SKIPPED` | |

### 17.3 Tables (V5)

`scheduled_jobs` holds the configuration (see the API guide for every field) plus runtime state written only by the scheduler:

| Column | Role |
| --- | --- |
| `next_run_at` | When it is due. NULL: disabled, finished (past `end_at`), or invalid. |
| `schedule_fingerprint` | SHA-256 of the raw timing columns `next_run_at` was computed from. A mismatch means "edited": recompute. NULL for a disabled row, so re-enabling starts from now instead of catching up. |
| `retry_attempt` | Consecutive failures of the current fire. |
| `locked_by`, `locked_until` | The lease: which instance runs it, until when unless renewed. |
| `last_run_at`, `last_status` | Denormalized for lists. |

Names are unique per company and among system rows: a stored generated `company_key = COALESCE(company_id, 0)` plus a `name_key` that is NULL once soft-deleted, under one unique key. CHECK constraints require the expression for its type and `end_at > start_at`. `updated_at` has no `ON UPDATE`, so the scheduler's own writes do not look like edits.

`scheduled_job_runs` copies `job_key` and `company_id` so history survives edits. `fire_key = "<scheduled_for epoch>:<attempt>"`, unique per schedule, makes **the database itself refuse to run the same fire twice**, a backstop under the lease. Manual runs have no fire key. Finished runs older than `scheduler.runRetention` are pruned by `scheduler.prune-runs`.

### 17.4 How it runs

Each instance runs one poll thread (`scheduler-poll`), a fixed worker pool (`scheduler-worker-N`) and a watchdog thread. Every `pollInterval`:

1. **Recompute.** Read every row (the table is small) and compare fingerprints. For changed rows, compute the next fire time inside the `[start_at, end_at]` window and store it, only if the fingerprint is still the one read (the first instance wins). An invalid row is logged once per edit and gets no `next_run_at`.
2. **Reap.** Lock rows whose lease expired (`FOR UPDATE SKIP LOCKED`), mark their `RUNNING` runs `ABANDONED`, clear the lease, and apply the retry rules.
3. **Claim**, in one short transaction, at most as many runs as there are idle workers, and only job keys registered on **this** instance (during a rolling deploy each instance runs what it knows):
   - due rows: a late fire is skipped or run once by `misfire_policy`. Otherwise take the lease, move `next_run_at` to the next fire after now, and insert a `RUNNING` run;
   - then `QUEUED` manual runs whose schedule is not leased, one per schedule.
4. **Dispatch** the claims to the workers. Nothing waits in memory: what isn't claimed stays in the database for the next poll or another instance.

While a job runs, the watchdog **renews the lease** every lease/3 and **enforces the timeout** (schedule's `timeout_seconds`, else the job type's, else `scheduler.defaultTimeout`). Cancelling sets `isCancelled()` and interrupts the worker. A lost lease, or renewal failing for 2/3 of the lease (database unreachable), also cancels the job rather than let it run alongside another instance.

**Finish**, in one transaction: record the outcome on the run, release the lease (only if still ours), and for scheduled runs apply the **retry rules**. A success resets `retry_attempt`. A failure (`FAILED`, `TIMED_OUT`, `ABANDONED`) with retries left sets `next_run_at = now + retry_backoff_seconds * 2^attempt` (capped at 1 hour), unless the next regular fire comes first. Manual runs are never retried.

**Shutdown** (`Main`'s hook, before the pool closes): stop claiming, wait `shutdownGrace` for running jobs, then interrupt them. Their runs end `ABANDONED` and their leases are released, so another instance retries them at once.

**What this guarantees**
- One fire of a schedule starts at most once across all instances (lease + `fire_key`), and two runs of one schedule never overlap.
- A crashed instance's runs are detected within one lease (1 minute) and retried if the schedule allows it. A job may therefore run again after a crash in the middle: **write jobs so that re-running them is safe.**
- Edits apply within one poll; a disabled or deleted schedule stops firing (a run already in progress finishes).

### 17.5 Admin API and permissions

Module `modules/system/scheduling` (`SchedulingModule`), routes under `/scheduler`: job types, schedules (CRUD with `version`, soft delete), previews (saved or unsaved), "run now" (`202`, one queued run per schedule) and run history.

| Permission | Allows |
| --- | --- |
| `scheduler.schedule.read` | the request company's schedules and runs, job types |
| `scheduler.schedule.manage` | create, change, delete the company's schedules |
| `scheduler.schedule.run` | run the company's schedules now (acting as the caller) |
| `scheduler.system.manage` | everything on install-wide schedules (without it they are invisible: `404`), and stack traces of failed runs |

On top of these, the job type's own `permission`, if any. The module edits the tables with its own repository; it never touches the engine (`platform.scheduler.internal`, enforced by ArchUnit).

### 17.6 Settings (`application.properties`)

| Key | Default | Meaning |
| --- | --- | --- |
| `scheduler.enabled` | `true` | `false`: this instance serves HTTP only |
| `scheduler.pollInterval` | `PT5S` | How often due schedules are looked for |
| `scheduler.workerThreads` | `4` | Jobs running at once on this instance |
| `scheduler.lease` | `PT1M` | Lock length without renewal; how fast a crash is detected |
| `scheduler.defaultTimeout` | `PT1H` | For job types and schedules without one |
| `scheduler.shutdownGrace` | `PT30S` | Wait for running jobs before interrupting them |
| `scheduler.runRetention` | `P90D` | History kept by `scheduler.prune-runs` |
| `scheduler.instanceId` | `host:pid:random` | Name of this instance in leases and runs |

### 17.7 Adding a job to a module

```java
// SalesModule
@Override
public void registerJobs(Scheduler scheduler, ServiceRegistry services) {
  scheduler.register(JobType.of("sales.daily-summary", "Emails yesterday's sales to the managers",
      ctx -> salesReports.sendDailySummary(ctx.requestContext(), ctx.parameters()))   // an orchestrator
      .withScope(JobType.Scope.COMPANY)                    // one schedule per company that wants it
      .withPermission("sales.report.read")                 // to schedule it, you must be allowed to read it
      .withTimeout(Duration.ofMinutes(10))
      .withParameters(p -> { /* throw ValidationFailedException.field("parameters.x", ...) */ }));
}
```

- Long loops call `ctx.throwIfCancelled()` between batches; `ctx.setResultSummary("42 invoices sent")` shows up in the history.
- A job that should exist on every install declares `.withDefaultSchedule(DefaultSchedule.cron(...))`. It is inserted on first start if no row (even a deleted one) uses the key, and never overwritten.
- Built in: `scheduler.prune-runs` (platform, daily 03:30) and `users.purge-expired-sessions` (hourly at :15), both SYSTEM-only.

### 17.8 Tests

See §10. The engine tests run the real scheduler against MySQL with test job types and a 100 ms poll. They cover two instances sharing 30 fires (each runs exactly once), crash recovery from a stale lease, retries with backoff, timeouts, both misfire policies, manual runs acting as the requester, edits made with SQL, and shutdown.

### 17.9 Known gaps and limits

- **Permissions are checked when a schedule is saved or run by hand, not at each scheduled run.** If the user who created a schedule loses the job's permission, the schedule keeps running (as SYSTEM) until someone changes it.
- **No alerting** on failures yet: `last_status`, run history and WARN logs only. Metrics (Micrometer) are still on the checklist (§12).
- **Minute granularity** for CRON (no seconds field); INTERVAL accepts whole seconds but at least `PT1M`.
- **DST:** a repeated local hour runs once, so an hourly CRON skips the extra hour in the fall. Use INTERVAL for "every N minutes of real time".
- **No calendars** (holidays, business days) and no job chaining or dependencies.
- **Every poll reads all schedules** to compare fingerprints: fine for hundreds to a few thousand rows, revisit beyond that.
- A run in progress when its schedule is deleted finishes normally; queued manual runs of it end `SKIPPED`.
