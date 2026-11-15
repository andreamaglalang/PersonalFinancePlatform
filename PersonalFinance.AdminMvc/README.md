# OWLI Administrator MVC — MVC-2

Independent .NET 10 server-rendered HTTPS client. There are no backend project references,
database dependencies, fake authentication modes, or Dashboard statistics.

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
