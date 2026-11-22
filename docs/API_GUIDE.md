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

Optional, to test permissions and multiple companies, see §7.

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
| 400 | `BAD_REQUEST`, `COMPANY_REQUIRED`, `INVALID_COMPANY_ID` | Malformed JSON / unknown property in a fixed body (login), no company chosen when you have several, bad `X-Company-Id` |
| 401 | `UNAUTHENTICATED`, `SESSION_INVALID`, `AUTH_HEADER_INVALID`, `INVALID_CREDENTIALS` | No / bad / expired token, wrong login |
| 403 | `PERMISSION_DENIED`, `COMPANY_ACCESS_DENIED` | Missing permission (named in `detail`), not a member of that company |
| 404 | `NOT_FOUND` | No such row **in your company** (another company's row is also 404) |
| 409 | `VERSION_CONFLICT`, `DUPLICATE_VALUE` | Someone changed the row since you read it; concurrent duplicate |
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

Every entity exposed generically gets the same five routes. `{base}` is `/masterdata/products` or `/masterdata/product-categories`.

| Method | Path | Body | Permission | Success |
| --- | --- | --- | --- | --- |
| `QUERY` | `{base}` | search spec (§6), may be empty | `.read` | `200 { "data": [rows], "page": {limit, offset} }` |
| `GET` | `{base}/{id}` | – | `.read` | `200 row` |
| `POST` | `{base}` | the new row's fields | `.create` | `201 row`, `Location: {base}/{id}` |
| `PATCH` | `{base}/{id}` | `version` + only the fields to change | `.update` | `200 row` |
| `DELETE` | `{base}/{id}?version=N` | – | `.delete` | `204` |

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

## 7. Optional: a second user and company, to test permissions

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

## 8. Things worth trying

**Authentication**
- [ ] Login with a wrong password, then an unknown user: same `401 INVALID_CREDENTIALS`, same message.
- [ ] Five wrong passwords in a row, then a sixth attempt (even with the right password): `429 LOGIN_RATE_LIMITED`.
- [ ] Login body with an extra property (`"companyId": 1`): `400`.
- [ ] Any route with `Authorization: Bearer garbage`: `401 SESSION_INVALID`. With `Basic ...`: `401 AUTH_HEADER_INVALID`.
- [ ] Logout, then reuse the token: `401`.
- [ ] `set-password U1` while logged in: every session of U1 ends.

**Companies and permissions** (after §7)
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

**Searching**
- [ ] `contains` with `"%"` or `"_"`: matched literally.
- [ ] Filter on `currencyId` → `422 NOT_FILTERABLE`; sort on `productCategoryId` → `NOT_SORTABLE`.
- [ ] Sort by `category.code`, then page with `limit: 1` and growing `offset`.
