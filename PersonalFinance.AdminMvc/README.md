# OWLI Administrator MVC — MVC-3 Dashboard UI

Independent .NET 10 server-rendered HTTPS client. There are no backend project references,
database dependencies, fake authentication modes, or Dashboard statistics.

## Dashboard scope and availability

MVC-3 adds a responsive, presentation-only Administrator Dashboard. It shows the display
name from the existing authenticated identity, an Administrator role label and static
capability descriptions. A missing display name uses a generic administrator fallback.
The name reflects the login identity; this UI does not fetch a fresh profile or make an
additional `/api/auth/me` call. Dynamic identity text uses normal Razor encoding.

The existing Administrator policy, backend session validation and anti-forgery-protected
POST logout remain unchanged. Navigation links only to the implemented Dashboard and
retains the existing logout form. Feature cards are informational, not navigation links.

- Default categories: administrator backend operations exist; the MVC management page is planned.
- User management: administrator API support is not available yet.
- Reports: administrator reporting and export support are not available yet.
- System monitoring: monitoring API support is not available yet; no health status is inferred.

Global statistics are omitted because current finance endpoints expose user-owned records,
not platform-wide administrative totals. There are no fabricated counts, financial totals,
charts, simulated live status or recent activity feeds. Future global summaries, user
management, reports, monitoring and audit activity require backend coordination with Andrea.
The default-category management UI requires a separately approved implementation scope.

This MVC-3 step received source inspection only. Build, Razor rendering, responsive visual
checks and authentication regression verification must follow separately; no build or tests
were run during this UI implementation.

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

No restore, build, tests, application startup, live HTTP calls, provisioning or migrations
were performed during implementation. Compile, Razor rendering, anti-forgery, cookie
behavior and refresh/logout concurrency require separately authorized offline verification
with synthetic HTTP responses before any live integration. Do not apply the unsafe
bill-payment migration as a setup step for this client.
