# OWLI Administrator MVC — MVC-4 Default Categories

Independent .NET 10 server-rendered HTTPS client. There are no backend project references,
database dependencies, fake authentication modes, or Dashboard statistics.

## Dashboard scope and availability

MVC-3 adds a responsive, presentation-only Administrator Dashboard. It shows the display
name from the existing authenticated identity, an Administrator role label and static
capability descriptions. A missing display name uses a generic administrator fallback.
The name reflects the login identity; this UI does not fetch a fresh profile or make an
additional `/api/auth/me` call. Dynamic identity text uses normal Razor encoding.

The existing Administrator policy, backend session validation and anti-forgery-protected
POST logout remain unchanged. Navigation includes Dashboard and Categories and retains
the existing logout form. The Default categories card has a working management shortcut;
unavailable features remain informational.

- Default categories: MVC API-page browsing, create, edit name/color and deactivate/reactivate are implemented using the existing default-category API.
- User management: administrator API support is not available yet.
- Reports: administrator reporting and export support are not available yet.
- System monitoring: monitoring API support is not available yet; no health status is inferred.

Global statistics are omitted because current finance endpoints expose user-owned records,
not platform-wide administrative totals. There are no fabricated counts, financial totals,
charts, simulated live status or recent activity feeds. Future global summaries, user
management, reports, monitoring and audit activity require backend coordination with Andrea.
## Shared default category management

Category actions require the existing Administrator policy and backend session validation.
Forms are server-rendered with anti-forgery validation; API bearer tokens stay server-side.
The category service shares the existing session gate with refresh/logout, rechecks session
state after acquiring it, and signs out on a category API 401 only after releasing the gate.
No authentication infrastructure or existing refresh/logout behavior is changed.

Index always requests IncludeInactive=true and supports Type, Page and PageSize.
PageSize must be 1–100. Page must be positive and satisfy the backend's supported
pagination offset; Page itself is not limited to 100.
The backend mixes the current user's personal categories with shared defaults before
pagination. MVC displays only IsSystemDefault=true from that single fetched API page.
Previous/next links navigate mixed API pages. No mixed TotalCount is shown as a default
total. An empty page means only that this API page contains no shared defaults. Pages can
shift during concurrent changes; there is no complete default-only inventory or global search.

Create uses POST /api/categories/defaults. Edit re-reads GET /api/categories/{id}, rejects
personal records, and uses PUT /api/categories/defaults/{id}. Type is immutable in MVC-4
and always taken from the trusted current response, never from posted Type/ownership fields.
Deactivation requires explicit confirmation; it affects all users, retains history and blocks
new use where the backend requires an active category. Inactive categories can be reactivated.
There is no DELETE UI or request and no fake category data or invented success notification.

Edit forms contain a Data Protection-protected, session-bound snapshot of nonsecret original
category fields, valid for 30 minutes. Before PUT, the current name/type/color/active state
must still match. Tampering, expiry or detected changes require an explicit reload. This is
best-effort only: the API has no ETag/rowversion precondition, and a concurrent update between
GET and PUT can still overwrite state. Atomic concurrency protection needs Andrea's backend
coordination. Protected snapshots are not access/refresh tokens and contain no credentials.

Duplicate/conflict, validation, authorization, missing-record, rate-limit, malformed-response
and unavailable-service states are handled without echoing upstream error bodies. Mutations
are never automatically retried; after an uncertain outcome, reload and check before resubmitting.
Default-only listing/search/counts and atomic conditional updates require backend coordination.

The initial MVC-3 UI implementation received source inspection only; its build and offline
authentication verification were performed separately. Actual browser visual checks remain pending.

## Configuration and MVC-2 authentication

Before a separately authorized startup, supply `BackendApi:BaseUrl` through the MVC
configuration provider. It must be an approved absolute HTTPS origin with no path,
credentials, query or fragment. No URL is supplied by default: startup fails validation
until it is configured. Do not copy backend connection strings or signing secrets here.
Use an approved HTTPS listener and trusted certificate for MVC and the API; do not bypass
certificate validation. Production proxy forwarding requires separately reviewed trusted
proxy configuration; arbitrary forwarded headers are not accepted.

The browser holds a protected opaque ticket reference, never API tokens. Up to 256 sessions
are kept in this process only. Idle expiry is 20 minutes; absolute expiry is the earlier of
eight hours and the original refresh expiry. Restart/eviction loses sessions and requires
login. Expired records are swept every minute; removal drops managed token references,
but .NET strings cannot be securely zeroed. There is no shared/distributed persistence.

Each authenticated request validates `/api/auth/me` before protected actions. Refreshes
within 60 seconds of access expiry are serialized with logout. Ambiguous refresh failures
invalidate the session without retry. Backend outages fail closed. Logout always removes
local authentication; remote revocation cannot be guaranteed during an outage. Existing
backend access JWTs may remain valid until expiry. Local expiry/restart does not itself
revoke remote refresh families; those expire according to backend rules.

All MVC traffic reaches the API from the server, so the existing per-IP authentication
limit can be shared by multiple administrators. No backend rate limits are changed.

## MVC-4 verification boundaries

Project-scoped .NET 10 restore/build with Razor compilation passed with zero warnings/errors.
Temporary outside-repository harnesses executed 62 TestHost pipeline checks (24 existing
MVC-2/MVC-3 checks plus 38 category checks), 26 direct synthetic checks and 27 focused
authentication checks: 115 grouped checks passed, zero failed, zero skipped. Coverage
overlaps across suites. The pipeline asserted TestServer, intercepted 178 synthetic backend
requests, and used no listener, certificates, database or real credentials. No live API calls,
provisioning or migrations were performed. Live deployment behavior and browser-based visual
and accessibility interaction remain unverified. Do not apply the unsafe bill-payment
migration as a setup step for this client.
