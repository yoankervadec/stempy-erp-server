# Stempy ERP API: manual testing guide

How to call the server by hand (Postman, curl): what the client must send, what comes back, and what to try.

A ready-made Postman collection lives next to this file: `docs/postman/stempy-erp.postman_collection.json` (Postman → Import). It stores the session token after login and threads ids and versions between requests.

---

## 1. Before you start

**Start the server**

```bash
./mvnw -q package -DskipTests
java -jar app/target/stempy-erp-server.jar          # http://localhost:8080 (server.port)
```

**An account that can do things.** You need a user with a password and a role in a company:

```bash
java -jar app/target/stempy-erp-server.jar set-password U1                  # prompts twice, no echo
java -jar app/target/stempy-erp-server.jar assign-role U1 LCS ADMINISTRATOR  # every permission in LCS
```

Your dev database already has `U1` with a password and `ADMINISTRATOR` in company `LCS` (id 1).

**Reference data products need.** A product points at a currency and a unit of measure. There are no endpoints for those yet, so insert them once in SQL (they are install-wide tables):

```sql
INSERT INTO currencies (code, is_enabled) VALUES ('CAD', TRUE);
INSERT INTO units_of_measure (code, description, is_enabled) VALUES ('UN', 'Unit', TRUE);
-- note the ids (SELECT id, code FROM currencies; ...): the collection assumes 1 and 1
```

Optional, to test permissions and multiple companies, see §10.

---

## 2. Conventions every request follows

| What | Rule |
| --- | --- |
| Base URL | `http://localhost:8080` |
| Body | JSON. Send `Content-Type: application/json` (Postman's raw/JSON does it). |
| Authentication | `Authorization: Bearer <token>`, the token from `POST /auth/login`. |
| Company | `X-Company-Id: <id>` picks the company the request acts in. Optional: without it the server uses your default company (or your only one). |
| Request id | Every response has `X-Request-Id`; send your own to correlate with the server log (it is printed on each log line). |
| Times | ISO-8601 UTC instants: `2026-11-22T15:04:05Z`. Whole seconds. |
| Decimals | Returned as JSON **strings** (`"0.2500"`) so no precision is lost. Send them as strings or numbers. |
| Ids | Integers, assigned by the server. |

**Login must be sent WITHOUT an `Authorization` header.** Any request carrying a bad or expired token gets `401 SESSION_INVALID`, even on public routes. (The collection's login request is set to "No Auth" for this reason.)

### Errors

Every error has the same shape (RFC 7807, `Content-Type: application/problem+json`):

```json
{
  "type": "urn:stempy:problem:validation_failed",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Validation failed",
  "instance": "/masterdata/products",
  "code": "VALIDATION_FAILED",
  "requestId": "3f0c9a5e-...",
  "errors": [
    { "field": "name", "code": "REQUIRED", "message": "is required" },
    { "field": "unitAmount", "code": "TOO_SMALL", "message": "must be at least 0" }
  ]
}
```

`code` is the stable thing to test against; `errors` only appears on validation failures and lists **every** field problem at once (at most one per field).

| Status | Typical `code` | Meaning |
| --- | --- | --- |
| 400 | `BAD_REQUEST`, `COMPANY_REQUIRED`, `INVALID_COMPANY_ID`, `INVALID_PARAMETER` | Malformed JSON / unknown property in a fixed body (login), no company chosen when you have several, bad `X-Company-Id`, bad query parameter (`limit`, `status`, ...) |
| 401 | `UNAUTHENTICATED`, `SESSION_INVALID`, `AUTH_HEADER_INVALID`, `INVALID_CREDENTIALS` | No / bad / expired token, wrong login |
| 403 | `PERMISSION_DENIED`, `COMPANY_ACCESS_DENIED` | Missing permission (named in `detail`), not a member of that company |
| 404 | `NOT_FOUND` | No such row **in your company** (another company's row is also 404) |
| 409 | `VERSION_CONFLICT`, `DUPLICATE_VALUE`, `RUN_ALREADY_QUEUED` | Someone changed the row since you read it; concurrent duplicate; a manual run of that schedule is already waiting |
| 422 | `VALIDATION_FAILED` (see `errors`: `REQUIRED`, `ALREADY_EXISTS`, `INVALID_REFERENCE`, ...), `INVALID_REFERENCE` | Field problems; a reference the pre-checks couldn't see (e.g. an unknown `currencyId`) |
| 429 | `LOGIN_RATE_LIMITED` | 5 failed logins for one user from one IP: locked 15 minutes |
| 503 | `DATABASE_BUSY` | Retry |

---

## 3. Health (no token)

| Request | Response |
| --- | --- |
| `GET /health/live` | `200 {"status":"UP"}`: the process runs |
| `GET /health/ready` | `200 {"status":"UP","checks":{"database":"UP"}}`, or `503` with `DOWN` |

---

## 4. Authentication

### `POST /auth/login` (no Authorization header)

```json
{ "userNo": "U1", "password": "your password" }
```

`userNo` is case-insensitive. Response `200` (also `Cache-Control: no-store`):

```json
{
  "token": "Qm9...43 characters",
  "expiresAt": "2026-11-29T15:00:00Z",
  "idleTimeoutSeconds": 28800,
  "user": { "id": 1, "userNo": "U1", "name": "Admin" },
  "companies": [ { "id": 1, "code": "LCS", "description": null } ],
  "defaultCompanyId": 1
}
```

- The token is shown only here. A session ends after 8 h without use, after 7 days in any case, at logout, or when the password changes.
- `defaultCompanyId` is `null` when you belong to several companies and have no default: then every company-scoped request needs `X-Company-Id`.
- Wrong user number and wrong password give the same `401 INVALID_CREDENTIALS`.

### `GET /auth/me`

Who you are in the request's company and what you may do there:

```json
{
  "user": { "id": 1, "userNo": "U1", "name": "Admin" },
  "company": { "id": 1, "code": "LCS", "description": null },
  "permissions": [ "masterdata.product.create", "masterdata.product.delete", "..." ]
}
```

### `POST /auth/logout`

`204`. Ends this session only (others stay valid). Doesn't need a company. Logging out twice: the second gets `401`.

---

## 5. Entity routes (products, product categories)

Every entity exposed generically gets the same six routes. `{base}` is `/masterdata/products` or `/masterdata/product-categories`.

| Method | Path | Body | Permission | Success |
| --- | --- | --- | --- | --- |
| `QUERY` | `{base}` | search spec (§6), may be empty | `.read` | `200 { "data": [rows], "page": {limit, offset} }` |
| `GET` | `{base}/{id}` | – | `.read` | `200 row` |
| `POST` | `{base}` | the new row's fields | `.create` | `201 row`, `Location: {base}/{id}` |
| `PATCH` | `{base}/{id}` | `version` + only the fields to change | `.update` | `200 row` |
| `DELETE` | `{base}/{id}?version=N` | – | `.delete` | `204` |
| `GET` | `{base}/{id}/history?limit=&offset=` | – | `.read` + `audit.history.read` | `200 { "data": [entry], "page": {limit, offset} }`, newest first |

Permissions are `masterdata.product.read|create|update|delete` and `masterdata.productCategory.read|create|update|delete`.

`QUERY` is a real HTTP method (a GET with a body). In Postman, click the method dropdown and **type** `QUERY`.

### Fields

Server-managed on every row (returned, never accepted in a body: sending one gives `READ_ONLY`):
`id`, `version`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `deletedAt`. The company is never shown or accepted.

**Product category**

| Field | Type | Create | Rules |
| --- | --- | --- | --- |
| `code` | string | required | max 50, unique in the company |
| `description` | string | required | max 255 |
| `isEnabled` | boolean | optional (default `true`) | |

**Product**

| Field | Type | Create | Rules |
| --- | --- | --- | --- |
| `productNo` | string | required | max 50, unique in the company |
| `name` | string | required | max 255 |
| `unitAmount` | decimal | required | ≥ 0, 4 decimals stored |
| `productCategoryId` | integer | required | a live category of **your** company |
| `unitOfMeasureId` | integer | required | an existing unit of measure |
| `currencyId` | integer | required | an existing currency |
| `isEnabled` | boolean | optional (default `true`) | |

### Create

```http
POST /masterdata/products
{ "productNo": "P-001", "name": "Hex bolt M8", "unitAmount": "0.25",
  "unitOfMeasureId": 1, "currencyId": 1, "productCategoryId": 1 }
```

Returns `201` with the full row (`version: 0`, `createdBy` = you, ...).

### Update: send the version you last read

```http
PATCH /masterdata/products/7
{ "version": 0, "name": "Hex bolt M8 zinc" }
```

Returns the updated row with `version: 1`. Send that `1` next time. If someone (or you, in another tab) saved in between, you get `409 VERSION_CONFLICT`: reload with `GET`, reapply, retry. `version` missing: `422` with `version: REQUIRED`. Only send the fields you change; `productNo` and `code` may be changed (still unique).

### Delete (soft)

```http
DELETE /masterdata/products/7?version=1
```

`204`. The row disappears from `GET` and `QUERY` but stays in the table, so its `productNo` stays taken (creating it again gives `ALREADY_EXISTS`).

### History: who changed what

```http
GET /masterdata/products/7/history?limit=50&offset=0
```

Every create, update and delete is recorded in the audit trail, in the same transaction as the change. The history lists a record's entries **newest first**:

```json
{ "data": [
    { "id": 42, "action": "UPDATED", "occurredAt": "2026-11-24T15:04:05Z",
      "user": { "id": 1, "userNo": "U1", "name": "Jane Roy" }, "requestId": "c4ff5f85-...",
      "changes": { "name": { "old": "Hex bolt M8", "new": "Hex bolt M8 zinc" } } },
    { "id": 17, "action": "CREATED", ..., "changes": { "productNo": { "old": null, "new": "P-001" }, ... } } ],
  "page": { "limit": 50, "offset": 0 } }
```

- Needs the record's `.read` permission **and** `audit.history.read` (`ADMINISTRATOR` has both); without it, `403 PERMISSION_DENIED`.
- `changes` lists only the fields whose value really changed (`"0.25"` resent as `"0.2500"` is not a change); a `PATCH` that changes nothing adds no entry. `DELETED` carries no changes. Decimals and dates are strings.
- A field you may not read shows as `{ "masked": true }`: you see **that** it changed, not to what.
- Only your company's entries are shown. A deleted record keeps its history (its `GET` is `404`, its history is not).
- `limit` 1 to 200 (default 50); a bad value is `400 INVALID_PARAMETER`.

---

## 6. Searching: the `QUERY` body

All parts optional; an empty body means "first 50 rows, by id".

```json
{
  "where": {
    "and": [
      { "field": "name", "op": "contains", "value": "bolt" },
      { "field": "unitAmount", "op": "lessThan", "value": "1.00" },
      { "not": { "field": "category.code", "op": "equals", "value": "OLD" } }
    ]
  },
  "sort": [ { "field": "category.code", "dir": "asc" }, { "field": "name", "dir": "desc" } ],
  "page": { "limit": 20, "offset": 0 }
}
```

**Filter nodes** (nest up to 4 levels, at most 20 conditions):
`{ "and": [ ... ] }`, `{ "or": [ ... ] }`, `{ "not": { ... } }`, or a condition `{ "field", "op", "value" }`.

**Operators**

| `op` | Value | For |
| --- | --- | --- |
| `equals`, `notEquals` | scalar | all types |
| `greaterThan`, `greaterThanOrEquals`, `lessThan`, `lessThanOrEquals` | scalar | numbers, decimals, dates/times, ids |
| `in` | array, 1 to 100 values | all but booleans |
| `contains`, `startsWith` | string, case-insensitive; `%` and `_` are literal | strings |
| `isNull` | `true` / `false` | all types |

**What you may filter / sort on**

| Entity | Filter | Sort |
| --- | --- | --- |
| Product | `id`, `productNo`, `name`, `unitAmount`, `productCategoryId`, `isEnabled` | `id`, `productNo`, `name`, `unitAmount` |
| Category | `id`, `code`, `description`, `isEnabled` | `id`, `code`, `description` |

Through **relations** (one hop is enough today, two allowed): `category.code`, `category.description` (products), and on both entities `createdByUser.userNo`, `createdByUser.name`, `updatedByUser.userNo`, `updatedByUser.name`. Example: `{ "field": "createdByUser.userNo", "op": "equals", "value": "U1" }`.

**Sort**: at most 3 keys, `dir` `asc` (default) or `desc`; ties are always broken by `id`.
**Page**: `limit` 1–200 (default 50), `offset` ≥ 0. The response echoes them; there is no total count.

Errors in the body are `422` with per-path codes, e.g. `UNKNOWN_FIELD`, `NOT_FILTERABLE`, `NOT_SORTABLE`, `UNKNOWN_OPERATOR`, `INVALID_VALUE`, `TOO_DEEP`, `TOO_MANY_CONDITIONS`.

---

## 7. Scheduler

Recurring jobs ("every 15 minutes", "daily at 07:00", "first Monday of the month"). **Job types** are code: each module registers what it can run (`GET /scheduler/job-types`). **Schedules** are rows of `scheduled_jobs` saying which job runs when, for which company, with which parameters. The server re-reads them every poll (`scheduler.pollInterval`, 5 s), so a change applies without a restart, also when made with plain SQL.

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `GET` | `/scheduler/job-types` | – | `.read` or `system` | `200 [jobType]` |
| `GET` | `/scheduler/schedules?scope=&limit=&offset=` | – | `.read` and/or `system` | `200 { "data": [schedule], "page": {limit, offset} }` |
| `POST` | `/scheduler/schedules` | the schedule's fields | `.manage` (`system` for scope `SYSTEM`) | `201 schedule`, `Location` |
| `GET` | `/scheduler/schedules/{id}` | – | `.read` (`system`) | `200 schedule` |
| `PATCH` | `/scheduler/schedules/{id}` | `version` + only the fields to change | `.manage` (`system`) | `200 schedule` |
| `DELETE` | `/scheduler/schedules/{id}?version=N` | – | `.manage` (`system`) | `204` |
| `GET` | `/scheduler/schedules/{id}/history?limit=&offset=` | – | `.read` (`system`) + `audit.history.read` | `200 { "data": [entry], "page": {...} }`, newest first (§5); not for a deleted schedule |
| `GET` | `/scheduler/schedules/{id}/preview?count=10` | – | `.read` (`system`) | `200 preview` |
| `POST` | `/scheduler/preview` | `scheduleType`, `cronExpression` / `interval`, `timeZone`, `startAt`, `endAt`, `count` | `.manage` or `system` | `200 preview` (nothing saved) |
| `POST` | `/scheduler/schedules/{id}/runs` | none, or `{ "parameters": {...} }` | `.run` (`system`) | `202 run`, `Location: /scheduler/runs/{id}` |
| `GET` | `/scheduler/runs?scheduleId=&status=&limit=&offset=` | – | `.read` and/or `system` | `200 { "data": [run], "page": {...} }`, newest first |
| `GET` | `/scheduler/runs/{id}` | – | `.read` (`system`) | `200 run` |

Permissions: `scheduler.schedule.read`, `scheduler.schedule.manage`, `scheduler.schedule.run` act on **the request's company**. `scheduler.system.manage` (`system` above) covers **install-wide** schedules (scope `SYSTEM`, e.g. the server's own housekeeping). Without it they are invisible (`404`). Lists default to `limit` 50 (max 500).

A job type may also name a **permission of its own** (`permission` in `GET /scheduler/job-types`). Creating, changing, deleting or running a schedule of that job then needs it too. Scheduled runs act as the `SYSTEM` user with every permission, so this check is what stops anyone from scheduling work they could not do themselves.

### Schedule fields

| Field | Type | Create | Rules |
| --- | --- | --- | --- |
| `name` | string | required | max 100, unique in the company (or among system schedules) |
| `description` | string | optional | max 255 |
| `jobKey` | string | required | a key from `GET /scheduler/job-types`; its `scope` must allow this schedule's scope |
| `scope` | `COMPANY` \| `SYSTEM` | optional (default `COMPANY`) | cannot be changed afterwards |
| `scheduleType` | `CRON` \| `INTERVAL` | required | |
| `cronExpression` | string | for `CRON` | see below; must be empty (`null`) for `INTERVAL` |
| `interval` | ISO-8601 duration | for `INTERVAL` | `PT1M` to `P366D`, e.g. `PT15M`, `PT2H`, `P1D`; must be empty for `CRON` |
| `timeZone` | IANA zone | optional (default `America/Toronto`) | a `CRON` is evaluated in it: 07:00 stays 07:00 local across DST |
| `parameters` | JSON object | optional | given to the job; checked by the job type |
| `isEnabled` | boolean | optional (default `true`) | |
| `startAt`, `endAt` | instant | optional | no run before `startAt` or after `endAt` |
| `misfirePolicy` | `FIRE_ONCE` \| `SKIP` | optional (default `FIRE_ONCE`) | a fire time found more than `misfireThresholdSeconds` late (server down): run once now, or skip it |
| `misfireThresholdSeconds` | int | optional (default 300) | 0 to 604800 |
| `timeoutSeconds` | int | optional (default: the job type's, else `scheduler.defaultTimeout`) | 1 to 604800; past it the run is interrupted, `TIMED_OUT` |
| `maxRetries`, `retryBackoffSeconds` | int | optional (0, 60) | 0 to 20; 1 to 86400. A failed run is retried after backoff × 2^n, capped at 1 hour |

Returned only (sending one gives `READ_ONLY`): `id`, `version`, `jobRegistered` (false: no such job type on this server), `nextRunAt`, `lastRunAt`, `lastStatus`, `retryAttempt`, `runningOn` (the server instance running it now), `createdAt`, `createdBy`, `updatedAt`, `updatedBy`.

On `PATCH`, a field left out keeps its value and `null` clears it. To switch a schedule from `CRON` to `INTERVAL`, send `"cronExpression": null` with the new `interval`. Every problem comes back in one `422`, with codes such as `INVALID_CRON`, `INVALID_TIME_ZONE`, `NOT_ALLOWED`, `UNKNOWN_JOB`, `NOT_ALLOWED_FOR_JOB`, `ALREADY_EXISTS`, `OUT_OF_RANGE`.

**Cron expressions**: five fields, `minute hour day-of-month month day-of-week`.

| Example | Means |
| --- | --- |
| `*/15 * * * *` | every 15 minutes |
| `0 7 * * *` | every day at 07:00 |
| `30 8 * * MON-FRI` | weekdays at 08:30 |
| `0 0 * * TUE` | every Tuesday at midnight |
| `0 9 * * MON#1` | the first Monday of the month at 09:00 |
| `0 17 * * 5L` | the last Friday of the month at 17:00 |
| `0 0 L * *` | the last day of the month |
| `0 6 1,15 * *` | the 1st and 15th at 06:00 |
| `@hourly`, `@daily`, `@weekly`, `@monthly`, `@yearly` | shortcuts |

Names (`JAN`, `MON`) are case-insensitive; `0` and `7` are both Sunday; `?` means "any day". When **both** day-of-month and day-of-week are restricted, a day matching **either** fires (`0 0 1 * MON` = the 1st and every Monday), as in standard cron. During DST changes, a time skipped by the spring-forward runs at 03:00, and a time that happens twice in the fall runs once. Use `GET .../preview` or `POST /scheduler/preview` to check an expression before relying on it.

```http
POST /scheduler/schedules
{
  "name": "Daily sales summary",
  "jobKey": "<a key from /scheduler/job-types>",
  "scheduleType": "CRON",
  "cronExpression": "0 7 * * MON-FRI",
  "timeZone": "America/Toronto",
  "maxRetries": 2
}
```

### Runs

A run is one execution: `trigger` is `SCHEDULE`, `RETRY` or `MANUAL`; `status` goes `QUEUED` (manual, waiting) → `RUNNING` → `SUCCEEDED`, `FAILED`, `TIMED_OUT` or `ABANDONED` (its server stopped). `SKIPPED` is a missed fire under `misfirePolicy: SKIP`, or a queued run whose schedule was deleted. A run also carries `resultSummary` and `errorMessage`. `errorDetail` (the stack trace) is shown only to holders of `scheduler.system.manage`.

**Run now** (`POST /scheduler/schedules/{id}/runs`) queues a run that acts **as you**. In a company it gets your own permissions there, checked again when it starts, and `requestedBy` records you. It works on a disabled schedule too, to try a job before turning it on, and it does not change the regular schedule. One queued run per schedule at a time: a second request gets `409 RUN_ALREADY_QUEUED`. If the schedule is running at that moment, the queued run starts when that run ends. Poll `GET /scheduler/runs/{id}` (the `Location` header) to see the outcome.

**Jobs that ship with the server** (install-wide, created on first start; change or delete them like any schedule):

| Job key | Default schedule | Does |
| --- | --- | --- |
| `scheduler.prune-runs` | `30 3 * * *` America/Toronto | deletes finished runs older than `scheduler.runRetention` (P90D); parameter `{"retention": "P30D"}` overrides it |
| `outbox.prune` | `45 3 * * *` America/Toronto | deletes delivered outbox messages older than `outbox.retention` (P7D) and dead ones older than `outbox.deadRetention` (P90D) |
| `audit.prune` | `0 4 * * *` America/Toronto | deletes audit entries older than `audit.retention` (P7Y, never less than P6Y); parameter `{"retention": "P10Y"}` overrides it |
| `users.purge-expired-sessions` | `15 * * * *` America/Toronto | deletes sessions that are logged out, expired or idle |

---

## 8. Document numbering

Numbers for issued documents, such as `INV-000042` or `CO-01203012346`. **Document types** are code: each module declares what it numbers and which values a caller passes in (`GET /numbering/document-types`). **Number series** are per company and say how the numbers look: a pattern, a mode, a start value. Each series keeps one **counter** per combination of parameter values, so every store and terminal counts on its own.

No module declares a document type yet (GL journal entries will be first), so on a running server `GET /numbering/document-types` returns `[]` and creating a series fails with `UNKNOWN_DOCUMENT_TYPE`. The tests use their own types.

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `GET` | `/numbering/document-types` | – | `.read` | `200 [documentType]` |
| `GET` | `/numbering/series?documentType=&limit=&offset=` | – | `.read` | `200 { "data": [series], "page": {limit, offset} }` |
| `POST` | `/numbering/series` | the series' fields | `.manage` | `201 series`, `Location` |
| `GET` | `/numbering/series/{id}` | – | `.read` | `200 series` |
| `PATCH` | `/numbering/series/{id}` | `version` + only the fields to change | `.manage` | `200 series` |
| `GET` | `/numbering/series/{id}/counters` | – | `.read` | `200 [counter]` |
| `POST` | `/numbering/series/{id}/counters/set-next` | `{ "params": {...}, "nextNumber": N }` | `.manage` | `200 counter` |
| `GET` | `/numbering/series/{id}/history?limit=&offset=` | – | `.read` + `audit.history.read` | `200 { "data": [entry], "page": {...} }`, newest first (§5) |

Permissions: `numbering.series.read` and `numbering.series.manage`, on **the request's company**. Another company's series is `404`. Series are never deleted: set `isEnabled` to `false`.

### Patterns

| Token | Means |
| --- | --- |
| `{SEQ:6}` | the counter, zero-padded to 6 digits (it grows past 6 instead of failing); exactly one per pattern |
| `{STORE_NO:3}` | a value the caller passes, left-padded with `0` to 3 characters; the width is required |
| anything else | literal text: printable ASCII, no spaces, no braces |

| Pattern | Values | Number |
| --- | --- | --- |
| `INV-{SEQ:6}` | | `INV-000042` |
| `CO-{STORE_NO:3}{TERMINAL_NO:2}{SEQ:6}` | store `12`, terminal `3`, counter 12346 | `CO-01203012346` |

Only the parameters the document type declares may appear. A pattern is at most 255 characters, and a number at most 50 at its padded widths. Parameter values are letters, digits and `-`, at most their width (longer is refused, never cut), and `12` and `012` are the same store.

### Series fields

| Field | Type | Create | Rules |
| --- | --- | --- | --- |
| `documentType` | string | required | a key from `GET /numbering/document-types`; cannot be changed afterwards |
| `code` | string | required | upper-case letters, digits, `-`, `_`, max 50; unique per document type in the company; cannot be changed (callers ask for a series by its code) |
| `description` | string | optional | max 255 |
| `pattern` | string | required | see above |
| `mode` | `GAPLESS` \| `GAPS_ALLOWED` | required | `GAPLESS`: a cancelled document gives its number back. `GAPS_ALLOWED`: it is lost, and nobody waits. Some types require `GAPLESS` (`gaplessRequired`) |
| `startValue` | int | optional (default 1) | the first number of every new counter; 1 to 999999999999999999 |
| `isDefault` | boolean | optional | the series used when a caller doesn't name one. On create, left out: `true` if the type has no default yet. Making a series the default takes the flag off the old one. It can't be set to `false`: make another series the default instead (`CANNOT_UNSET`) |
| `isEnabled` | boolean | optional (default `true`) | a disabled series gives no numbers (`409 NUMBER_SERIES_NOT_FOUND` to the caller) |

Returned only: `id`, `version`, `typeRegistered` (false: no module on this server declares the type), `used` (it has a counter), `createdAt`, `createdBy` (`null`: created by the server, see below), `updatedAt`, `updatedBy`.

- **A used series keeps its shape.** Once it has a counter, `pattern`, `mode` and `startValue` can't change (`409 NUMBER_SERIES_IN_USE`). To change the format, create a new series and make it the default.
- **Built-in default.** A company with no default series for a type gets the type's own default (e.g. `CO`) on the first number. It then shows up here with `createdBy: null`. A disabled default is never replaced.
- Validation codes: `UNKNOWN_DOCUMENT_TYPE`, `INVALID_PATTERN` (the message says what and where), `GAPLESS_REQUIRED`, `ALREADY_EXISTS`, `INVALID_FORMAT`, `OUT_OF_RANGE`, `READ_ONLY`.

```http
POST /numbering/series
{
  "documentType": "<a key from /numbering/document-types>",
  "code": "WEB",
  "description": "Online orders",
  "pattern": "WEB-{SEQ:6}",
  "mode": "GAPS_ALLOWED"
}
```

### Counters

`GET .../counters` lists each counter: `scope` (`STORE_NO=012;TERMINAL_NO=03`, or `""` for a pattern without parameters), `params` (the same as values), `lastNumber`, `nextNumber` and `next` (that number as it will print).

**Continue an old system's numbering** with `set-next`. It creates the counter if needed:

```http
POST /numbering/series/{id}/counters/set-next
{ "params": { "STORE_NO": "12", "TERMINAL_NO": "3" }, "nextNumber": 5001 }
```

A counter only moves up (`409 COUNTER_CANNOT_DECREASE`): lowering it would issue numbers again. Sending the current next number changes nothing. If a gapless document is being saved on that counter at that moment, the request waits for it. Each move is in the series' history as `COUNTER_SET`.

## 9. General ledger

The books of the request's company: the **chart of accounts**, **fiscal years** with their periods, **dimensions**, **journal entries**, and two **reports**, the trial balance and the account ledger (docs/ARCHITECTURE.md §22).

### Accounts

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `QUERY` | `/gl/accounts` | a search (§6) | `gl.account.read` | `200 { "data": [account], "page": {...} }` |
| `GET` | `/gl/accounts/{id}` | – | `gl.account.read` | `200 account` |
| `GET` | `/gl/accounts/{id}/history` | – | `gl.account.read` + `audit.history.read` | `200 { "data": [entry], "page": {...} }` (§5) |
| `POST` | `/gl/accounts` | the account's fields | `gl.account.manage` | `201 account`, `Location` |
| `PATCH` | `/gl/accounts/{id}` | `version` + only the fields to change | `gl.account.manage` | `200 account` |

| Field | Type | Create | Rules |
| --- | --- | --- | --- |
| `accountNo` | string | required | letters, digits, `.`, `-`, `_`, max 20; unique in the company, ignoring case. Sorted as text: use a fixed width (`1000`, `1010`) |
| `name` | string | required | max 100 |
| `description` | string | optional | max 255 |
| `accountType` | `ASSET` \| `LIABILITY` \| `EQUITY` \| `REVENUE` \| `EXPENSE` | required | debit-normal: `ASSET`, `EXPENSE` |
| `parentId` | id or `null` | optional | a **heading** account of the same company, for grouping. Not the account itself or one of its sub-accounts (`CYCLE`) |
| `isHeading` | boolean | optional (`false`) | a heading groups accounts and never takes postings. A heading with sub-accounts stays one (`HAS_CHILDREN`) |
| `manualPosting` | boolean | optional (`true`) | `false` for control accounts (receivables, payables, tax): only their module may post there, never a manual entry |
| `isEnabled` | boolean | optional (`true`) | accounts are never deleted: disable them |

Returned only: `id`, `companyId`, `version`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `deletedAt` (always `null`). Searches can use `parent.accountNo` (and other `parent.` fields) as well. Validation codes: `ALREADY_EXISTS`, `INVALID_FORMAT`, `INVALID_REFERENCE` (no such account in this company), `NOT_A_HEADING`, `CYCLE`, `HAS_CHILDREN`.

```http
POST /gl/accounts
{ "accountNo": "1000", "name": "Current assets", "accountType": "ASSET", "isHeading": true }

POST /gl/accounts
{ "accountNo": "1010", "name": "Bank", "accountType": "ASSET", "parentId": <the heading's id> }
```

### Fiscal years and periods

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `GET` | `/gl/fiscal-years` | – | `gl.period.read` | `200 [fiscalYear]`, oldest first, each with its `periods` |
| `POST` | `/gl/fiscal-years` | `{ "startDate": "2026-04-01" }` | `gl.period.manage` | `201 fiscalYear` |
| `GET` | `/gl/periods/{id}` | – | `gl.period.read` | `200 period` |
| `POST` | `/gl/periods/{id}/close` | – | `gl.period.manage` | `200 period` |
| `POST` | `/gl/periods/{id}/reopen` | – | `gl.period.reopen` | `200 period` |
| `GET` | `/gl/periods/{id}/history?limit=&offset=` | – | `gl.period.read` + `audit.history.read` | `200 { "data": [entry], "page": {...} }`: closes and reopens |

- A fiscal year is 12 monthly periods, created together, all `OPEN`. It starts on the 1st of a month (`NOT_FIRST_OF_MONTH`). The company's first year may start in any month; each later one must start the day after the previous one ends (`NOT_CONTIGUOUS`). So the first year sets the company's year-end.
- Names: a year starting in January is `2026`, otherwise `2026-2027`. A period is named by its month (`2026-04`).
- Entries (once posting exists) must be dated in an `OPEN` period. Closing a period stops all posting into it. Reopening is a separate permission, so the person who closes the books isn't necessarily allowed to reopen them. Closing a closed period, or reopening an open one, is `409 PERIOD_ALREADY_CLOSED` / `PERIOD_ALREADY_OPEN`.
- Another company's periods are `404`.

### Dimensions

Extra tags on journal lines beyond the account, defined by each company: `STORE`, `PROJECT`, `DEPARTMENT`... and their values (`STORE` = `012` "Downtown"). Journal entries will carry them; reports by account ignore them.

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `QUERY` / `GET` / `GET .../history` | `/gl/dimensions`, `/gl/dimension-values` | a search (§6) | `gl.dimension.read` | as for products (§5) |
| `POST` | `/gl/dimensions` | `{ "code": "STORE", "name": "Stores" }` | `gl.dimension.create` | `201 dimension` |
| `POST` | `/gl/dimension-values` | `{ "dimensionId": 3, "code": "012", "name": "Downtown" }` | `gl.dimension.create` | `201 value` |
| `PATCH` | `/gl/dimensions/{id}`, `/gl/dimension-values/{id}` | `version` + `name` and/or `isEnabled` | `gl.dimension.update` | `200` |
| `GET` | `/gl/accounts/{id}/dimension-rules` | – | `gl.account.read` | `200 [ { "dimensionCode", "rule" } ]` |
| `PUT` | `/gl/accounts/{id}/dimension-rules` | `{ "version": N, "rules": { "PROJECT": "REQUIRED", "STORE": "NOT_ALLOWED" } }` | `gl.account.manage` | `200` the rules |

- **Codes are fixed once created** (a `PATCH` of `code` is refused). Dimension codes: upper case, digits, `_`, max 20, unique in the company. Value codes: letters, digits, `.`, `-`, `_`, max 50, unique **within their dimension**: a duplicate is `409 DUPLICATE_VALUE`. Codes match ignoring case.
- Nothing is deleted: disable a dimension or a value (`isEnabled: false`) and it takes no new postings.
- Values can be searched by their dimension: `{"where": {"field": "dimension.code", "op": "equals", "value": "STORE"}}`.
- **Account rules** say which dimensions an account's lines must carry (`REQUIRED`) or may not carry (`NOT_ALLOWED`); any other dimension is optional. `PUT` replaces all of them (`{}` removes them all). Rules are part of the account: send the **account's** `version`, which goes up by one, and the change shows in the account's history as `dimensionRules`. Codes: `UNKNOWN_DIMENSION`, `INVALID_VALUE`.

### Journal entries

| Method | Path | Body | Permission | Returns |
| --- | --- | --- | --- | --- |
| `POST` | `/gl/journal-entries` | the entry (below); optional `Idempotency-Key` header | `gl.journal.post` | `201 entry`, `Location` |
| `GET` | `/gl/journal-entries?from=&to=&sourceType=&limit=&offset=` | – | `gl.journal.read` | `200 { "data": [entry without lines], "page": {...} }`, newest posting date first |
| `GET` | `/gl/journal-entries/{id}` | – | `gl.journal.read` | `200 entry` with its lines |
| `POST` | `/gl/journal-entries/{id}/reverse` | `{ "postingDate": "2026-02-01", "description": "optional" }` | `gl.journal.reverse` | `201 entry` (the reversal), `Location` |
| `GET` | `/gl/journal-entries/{id}/history?limit=&offset=` | – | `gl.journal.read` + `audit.history.read` | `200 { "data": [entry], "page": {...} }`: `POSTED`, `REVERSED` |

```http
POST /gl/journal-entries
Idempotency-Key: 2f1c6a52-6d0e-4b8e-9a3c-0c5f0b7f4a11
{
  "postingDate": "2026-01-15",
  "description": "Office supplies",
  "lines": [
    { "accountNo": "5000", "debit": "123.45", "description": "Paper", "dimensions": { "PROJECT": "P1" } },
    { "accountNo": "1010", "credit": "123.45" }
  ]
}
```

- **Lines:** at least two, at most 1,000. Each has an `accountNo` and exactly one of `debit` / `credit` above zero, as a string (`"123.45"`) or a number, rounded to the company's currency (2 decimals in CAD). Debits must equal credits (`UNBALANCED`). `description` and `dimensions` (`{dimension code: value code}`) are optional.
- **Accounts:** of this company, enabled, not a heading (`UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED`, `HEADING_ACCOUNT`). Control accounts (`manualPosting: false`) take no manual entries (`CONTROL_ACCOUNT`). Each account's dimension rules apply (`DIMENSION_REQUIRED`, `DIMENSION_NOT_ALLOWED`).
- **Period:** the posting date must fall in an `OPEN` period: `409 PERIOD_NOT_FOUND` (no fiscal year covers it) or `PERIOD_CLOSED`.
- **Numbers** are `JE00000001`, `JE00000002`... per company, with no gaps: a refused entry takes no number. The format can be changed with a number series for `gl.journal-entry` (§8).
- **Posted entries never change.** To correct one, reverse it: the reversal has the same lines with debit and credit swapped, on a date in an open period (the original may be in a closed one), and both entries point at each other (`reversalOfId`, `reversedById`). An entry is reversed once (`ALREADY_REVERSED`); a reversal can't be reversed (`CANNOT_REVERSE_A_REVERSAL`): post a new entry instead.
- **The entry returned:** `id`, `entryNo`, `postingDate`, `periodId`, `description`, `sourceType` (`MANUAL`, or e.g. `sales.invoice` for entries other modules post), `sourceId`, `sourceNo`, `reversalOfId`, `reversedById`, `currency`, `total`, `postedAt`, `postedBy`, and `lines`: `lineNo`, `accountId`, `accountNo`, `accountName`, `debit`, `credit` (one is `"0.00"`), `description`, `dimensions`.

**Idempotency-Key** (optional, recommended for any client that retries): send a new unique value, such as a UUID, with each new entry. If the response is lost and you send the same request again with the same key, the entry is not posted twice: you get the first one back, with the header `Idempotent-Replayed: true`. The same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`. A request that was refused (422, 409) keeps nothing, so you can fix it and resend with the same key. Keys are remembered for 7 days, per user.

### Reports

| Method | Path | Permission | Returns |
| --- | --- | --- | --- |
| `GET` | `/gl/reports/trial-balance?from=&to=[&dimension=&value=][&includeZero=true]` | `gl.report.read` | `200` the trial balance |
| `GET` | `/gl/reports/account-ledger?accountNo=&from=&to=[&dimension=&value=]` | `gl.report.read` | `200` the account's lines |

- **`from` and `to` are required** (`to` on or after `from`). Both reports can be limited to one dimension value: `dimension=STORE&value=012` (both, or neither).
- **Amounts are signed, debit-positive:** a debit balance is positive, a credit balance negative (sales show as `-1050.00`). `closing = opening + debits - credits`.
- **Opening** is everything before `from`, for every account. There is no year-end close yet, so revenue and expense openings include earlier years.
- **Trial balance:** `rows` follow the chart (account-number order, each heading before its accounts, `level` = depth), each with `amounts {opening, debits, credits, closing}`. A heading shows the sum of what's under it. Accounts with nothing to show are left out unless `includeZero=true`. `totals` adds up the posting accounts: when the books balance, opening and closing are `0.00` and debits equal credits.
- **Account ledger:** one posting account's `opening`, `debits`, `credits`, `closing`, and its `lines` in date order: `entryId`, `entryNo`, `lineNo`, `postingDate`, `description` (the line's, else the entry's), `sourceType`, `sourceNo`, `debit`, `credit`, the running `balance`, `dimensions`. At most 5,000 lines (`TOO_MANY_LINES`: narrow the dates). Codes: `UNKNOWN_ACCOUNT`, `HEADING_ACCOUNT`.
- Other codes: `REQUIRED`, `BEFORE_FROM`, `UNKNOWN_VALUE` (no such dimension value); a date that doesn't parse is `400 INVALID_PARAMETER`.

## 10. Optional: a second user and company, to test permissions

```sql
INSERT INTO companies (code, description, is_enabled) VALUES ('DEMO', 'Demo company', TRUE);

-- a read-only role
INSERT INTO roles (code, name) VALUES ('CATALOG_READER', 'Catalog reader');
INSERT INTO role_permissions (role_id, permission)
SELECT id, p FROM roles, (SELECT 'masterdata.product.read' p UNION ALL SELECT 'masterdata.productCategory.read') x
WHERE code = 'CATALOG_READER';

-- a second user (created_by/updated_by: any existing user id)
INSERT INTO users (user_no, name, created_by, updated_by) VALUES ('U2', 'Reader', 1, 1);
```

```bash
java -jar app/target/stempy-erp-server.jar set-password U2
java -jar app/target/stempy-erp-server.jar assign-role U2 LCS CATALOG_READER   # U2 reads LCS
java -jar app/target/stempy-erp-server.jar assign-role U1 DEMO ADMINISTRATOR   # U1 now has 2 companies
```

`assign-role` runs in its own process, and SQL changes bypass the server, so a running server sees these role and override changes within 5 minutes (`auth.permissions.cacheTtl`). Restart the server to see them at once.

Per-user overrides (no command yet; SQL):

```sql
-- take product deletion away from U1 in LCS, even though ADMINISTRATOR has everything
INSERT INTO user_permission_overrides (user_id, company_id, permission, effect)
SELECT u.id, c.id, 'masterdata.product.delete', 'DENY' FROM users u, companies c
WHERE u.user_no = 'U1' AND c.code = 'LCS';
```

---

## 11. Things worth trying

**Authentication**
- [ ] Login with a wrong password, then an unknown user: same `401 INVALID_CREDENTIALS`, same message.
- [ ] Five wrong passwords in a row, then a sixth attempt (even with the right password): `429 LOGIN_RATE_LIMITED`.
- [ ] Login body with an extra property (`"companyId": 1`): `400`.
- [ ] Any route with `Authorization: Bearer garbage`: `401 SESSION_INVALID`. With `Basic ...`: `401 AUTH_HEADER_INVALID`.
- [ ] Logout, then reuse the token: `401`.
- [ ] `set-password U1` while logged in: every session of U1 ends.

**Companies and permissions** (after §10)
- [ ] U1 with two companies: `GET /auth/me` without `X-Company-Id` uses the default; with `X-Company-Id: <DEMO id>` you see DEMO.
- [ ] `X-Company-Id: 999` → `403 COMPANY_ACCESS_DENIED`; `X-Company-Id: abc` → `400 INVALID_COMPANY_ID`.
- [ ] Data created in LCS is invisible (`404` / not listed) with `X-Company-Id: <DEMO id>`.
- [ ] U2 (reader): `QUERY` works, `POST` / `PATCH` / `DELETE` → `403 PERMISSION_DENIED` naming the permission.
- [ ] The DENY override: U1 can still create and update products in LCS, but not delete them.

**Writes**
- [ ] Create with an empty body `{}`: one `422` listing every required field.
- [ ] Send `createdBy`, `id` or `version` in a create: `READ_ONLY`. Send `companyId` or a made-up field: `UNKNOWN_FIELD`.
- [ ] `unitAmount: "-1"` → `TOO_SMALL`; `"abc"` → `INVALID_VALUE`.
- [ ] Duplicate `productNo` → `ALREADY_EXISTS` (also for a soft-deleted product's number).
- [ ] `productCategoryId` of a category from DEMO, or a deleted one → `INVALID_REFERENCE`. `currencyId: 999` → `422 INVALID_REFERENCE`.
- [ ] Two PATCHes with the same `version`: the second is `409 VERSION_CONFLICT`.
- [ ] Delete without `?version=` → `422`; with a wrong version → `409`; then `GET` → `404`.

**Scheduler**
- [ ] `POST /scheduler/preview` with `"cronExpression": "0 9 * * MON#1"`: the first Monday of each coming month. With `"0 0 30 2 *"`: `422 INVALID_CRON` ("never fires").
- [ ] Create a schedule with `"cronExpression": "*/1 * * * *"` and a job from `/scheduler/job-types`; within a minute `GET /scheduler/runs?scheduleId=...` shows a `SUCCEEDED` run.
- [ ] `PATCH` it with `"isEnabled": false`: `nextRunAt` becomes `null` within a few seconds, and no new runs appear.
- [ ] Run now twice quickly: the second is `409 RUN_ALREADY_QUEUED`. The run's `requestedBy` is you.
- [ ] As U2 (no `scheduler.system.manage`): the `SYSTEM` schedules are missing from the list, and `GET` on one is `404`.
- [ ] Stop the server while a long job runs: its run ends `ABANDONED`, and a server still running takes the schedule over.

**Searching**
- [ ] `contains` with `"%"` or `"_"`: matched literally.
- [ ] Filter on `currencyId` → `422 NOT_FILTERABLE`; sort on `productCategoryId` → `NOT_SORTABLE`.
- [ ] Sort by `category.code`, then page with `limit: 1` and growing `offset`.
