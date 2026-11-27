# Deployment and Cloudflare (optional)

**Production deployment is handled by the external deployment team; see the [deployment handoff guide](../../deployment/README.md).** OWLI runs fully locally without Cloudflare. Use this only if the owner asks you to expose the API or MVC site.

## What exists on the original PC (observed read-only on 2026-11-21; nothing was changed)

- `cloudflared` is installed as a Windows service (automatic start) and runs a **remotely managed tunnel**:
  `cloudflared tunnel run --token-file <ProgramData path>`. There is **no local `config.yml`**. The public hostnames
  and their mapping to local services are defined in the **Cloudflare Zero Trust dashboard**, so they are not visible
  in, and not reproducible from, this repository.
- At the time of inspection, the API and MVC were running locally as Debug builds from the deployment worktree.
- The tunnel token is a credential. It is **not** in GitHub and must never be committed, pasted or transferred
  through the repository.

## Concepts for a new PC

| Local service | Local URL | Tunnel concept |
| --- | --- | --- |
| Web API | `https://localhost:7027` | Public hostname → `https://localhost:7027` (origin uses a dev/self-signed certificate, so the tunnel's "No TLS Verify" or an origin CA would be a dashboard setting; decide deliberately, don't default to it) |
| MVC admin site | `https://localhost:62978` | Separate public hostname → `https://localhost:62978` |

Dependencies and assumptions:
- Both apps require `Request.IsHttps = true` and reject or redirect HTTP. Forward to the **HTTPS** origin.
  Forwarded-headers middleware is **not** enabled (see `deployment/README.md` §proxy). Do not globally trust
  forwarded headers.
- When exposed, set `AllowedHosts` to the real hostnames, and `BackendApi__BaseUrl` (MVC) to an API origin reachable
  from the MVC server.
- The Android **debug** build only talks to `https://localhost:7027`. A tunnel hostname would need a reviewed release
  configuration with a publicly trusted certificate. That is not implemented.
- Keep `Hangfire__Enabled=false` and never expose `/api/dev/*` (it is 404 outside Development anyway).

## Safe steps on a new PC (only with owner approval)

1. Install `cloudflared` from Cloudflare's official site.
2. The owner creates a **new** tunnel (or a new connector token) in the Cloudflare dashboard. Do not reuse or copy the
   original PC's token file.
3. Store the token outside the repository (e.g. a protected ProgramData file referenced by `--token-file`).
4. Configure public hostnames in the dashboard to the local HTTPS origins above.
5. Do not change the original tunnel, DNS or the running original services.

## Server deployment package

`deployment/` contains a publish script, **placeholder-only** env templates (`api.env.template`, `mvc.env.template`,
using `.example` hostnames), and the offline validation record `deployment/VALIDATION.md`. It is marked
**NOT YET APPROVED FOR LIVE DEPLOYMENT**. Read `deployment/README.md` before any server work.
