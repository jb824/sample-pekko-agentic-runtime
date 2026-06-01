# Plan: Seamless Multi-Tenant Google / YouTube Credential Management

## Goal

Make YouTube onboarding nearly effortless for tenants:

1. Tenant clicks "Connect YouTube".
2. Tenant signs in with Google and grants consent.
3. App discovers the tenant's YouTube channels.
4. Tenant selects a channel.
5. Web backend stores encrypted OAuth tokens and channel metadata.
6. Adapter workers ingest comments for that tenant without per-tenant env vars.

The default tenant experience should not require Google Cloud Console access, IAM roles, API enablement, service accounts, JSON files, or command-line setup.

## Current State

Today the Quarkus adapter supports:

- One process-wide Google credential source via `GOOGLE_CREDENTIALS_PATH`.
- One YouTube channel via `GOOGLE_YOUTUBE_CHANNEL_ID`.
- One YouTube tenant via `GOOGLE_YOUTUBE_TENANT_ID`.
- YouTube comment polling through `commentThreads.list`.
- Default YouTube OAuth scope: `https://www.googleapis.com/auth/youtube.force-ssl`.
- Mock Google event endpoints for local development.

This is fine for development, but it is not suitable for SaaS-style multi-tenant onboarding.

## Key Google Constraints

- The YouTube Data API v3 must be enabled on the Google Cloud project whose OAuth client is used.
- Programmatically enabling `youtube.googleapis.com` requires `serviceusage.services.enable` on that Google Cloud project.
- Tenants should not need GCP IAM roles if we own the OAuth app and Google Cloud project.
- For unattended polling, request offline access and store a refresh token.
- Existing refresh tokens do not automatically gain new scopes. If scopes change, the tenant must re-consent.
- YouTube channel access is controlled by the Google account that authorizes OAuth.

References:

- YouTube Data API setup: https://developers.google.com/youtube/v3/getting-started
- YouTube OAuth server-side flow: https://developers.google.com/youtube/v3/guides/auth/server-side-web-apps
- YouTube `commentThreads.list`: https://developers.google.com/youtube/v3/docs/commentThreads/list
- OAuth scopes: https://developers.google.com/identity/protocols/oauth2/scopes
- Service Usage enable API: https://docs.cloud.google.com/service-usage/docs/reference/rest/v1/services/enable
- Service Usage IAM: https://docs.cloud.google.com/service-usage/docs/access-control

## Recommended Product Model

### Default: Hosted OAuth App

Use one Google Cloud project owned by this application.

Tenant requirements:

- A Google account with access to the YouTube channel.
- Ability to approve the app's OAuth consent request.

Application owner requirements:

- Enable `youtube.googleapis.com` once.
- Configure OAuth consent screen.
- Create OAuth client credentials.
- Complete Google app verification if required for production use and sensitive scopes.
- Store the OAuth client secret in the server secret manager.

This is the lowest-friction path because tenants never touch GCP.

### Optional: BYO Google Cloud Project

Offer this only for enterprise tenants who require their own Google Cloud project, quota, billing, or compliance boundary.

Tenant requirements:

- Tenant provides a project ID or project number.
- Tenant grants our deployment identity enough permission to enable services, or enables the API themselves.
- Tenant creates/provides OAuth client credentials, or delegates setup through an admin workflow.

Minimum role needed to enable APIs:

- `roles/serviceusage.serviceUsageAdmin`

This is not the default because it is not effortless.

## Target Architecture

### Service Split

Split Quarkus into two services when real tenant onboarding/OAuth is added:

```text
quarkus-adapter/
  Google/YouTube/GBP pollers and sync jobs
  Guardian/HackerNews/Google inbound adapters
  Kafka producers and consumers
  Canonical event mapping
  Watermark/checkpoint handling
  Integration read/write workers

quarkus-web/
  User auth and sessions
  Tenant/account management
  Google OAuth connect/callback UX
  Integration settings
  Channel selection UI
  Dashboard/read APIs
  Approval queue for write actions
  Audit/admin UI

quarkus-common/
  Shared event schemas
  DTOs
  tenant IDs and validation
  API/error contracts
```

Boundary rule:

- `quarkus-web` owns user-facing flows, OAuth redirects/callbacks, tenant state, and action approvals.
- `quarkus-adapter` owns external-source ingestion, polling, event streams, and canonical event production/consumption.
- Pekko owns LLM-backed reasoning and agentic assistance.
- Shared contracts live in `quarkus-common`; do not duplicate event schemas.

This split avoids mixing browser/session/OAuth concerns with long-running ingestion and Kafka worker concerns.

### Core Components

- `GoogleOAuthResource` in `quarkus-web`
  - Starts OAuth authorization.
  - Handles OAuth callback.
  - Validates `state`.
  - Exchanges authorization code for tokens.

- `GoogleCredentialService` in `quarkus-web`
  - Builds authorization URLs.
  - Exchanges authorization codes.
  - Refreshes access tokens.
  - Revokes/disconnects tokens.
  - Normalizes Google API errors into tenant-facing statuses.

- `GoogleCredentialStore` shared interface, implemented by `quarkus-web` and read by `quarkus-adapter`
  - Stores encrypted refresh tokens.
  - Stores token metadata, scopes, expiry, and connection status.
  - Never logs raw tokens.

- `YouTubeChannelDiscoveryService` in `quarkus-web`
  - Uses the tenant's token to call `channels.list(mine=true)`.
  - Presents available channels for selection.
  - Stores selected channel IDs per tenant.

- `TenantYouTubeConnectionRepository` shared persistence contract
  - Owns durable tenant-to-channel mappings.
  - Supports multiple channels per tenant.
  - Supports per-channel status, watermark, and poll cadence.

- `TenantYouTubePoller` in `quarkus-adapter`
  - Replaces process-wide `GOOGLE_YOUTUBE_CHANNEL_ID`.
  - Iterates active tenant channel connections.
  - Refreshes each tenant token.
  - Calls `commentThreads.list`.
  - Emits canonical inbound events with the correct `tenantId`.

## Data Model

Add persistent tables similar to:

```sql
CREATE TABLE agent.google_connections_by_tenant (
  tenant_id text,
  connection_id text,
  provider text,
  google_subject text,
  email text,
  display_name text,
  scopes set<text>,
  encrypted_refresh_token text,
  token_version int,
  status text,
  error_code text,
  connected_at timestamp,
  updated_at timestamp,
  PRIMARY KEY ((tenant_id), connection_id)
);

CREATE TABLE agent.youtube_channels_by_tenant (
  tenant_id text,
  channel_id text,
  connection_id text,
  title text,
  handle text,
  thumbnail_url text,
  poll_enabled boolean,
  last_poll_at timestamp,
  last_success_at timestamp,
  last_error_code text,
  next_page_token text,
  high_watermark timestamp,
  created_at timestamp,
  updated_at timestamp,
  PRIMARY KEY ((tenant_id), channel_id)
);
```

Use Cassandra for local parity if that remains the repo standard, but production should strongly consider a transactional database for OAuth token state because token updates and connection lifecycle operations benefit from strong consistency.

## Tenant Onboarding Flow

### 1) Connect Button

Tenant clicks:

```text
GET /integrations/google/youtube/connect?tenantId=<tenant-id>
```

`quarkus-web` creates an OAuth `state` record:

- `tenantId`
- CSRF nonce
- requested integration: `youtube`
- redirect destination
- expiry timestamp

### 2) OAuth Redirect

Redirect tenant to Google with:

- `client_id`
- `redirect_uri`
- `response_type=code`
- `scope=https://www.googleapis.com/auth/youtube.force-ssl`
- `access_type=offline`
- `include_granted_scopes=true`
- `prompt=consent` only when a refresh token is missing or scopes changed
- signed/random `state`

Avoid asking for Google Cloud scopes from ordinary tenants. They only need YouTube channel authorization.

### 3) OAuth Callback

Google redirects back:

```text
GET /integrations/google/oauth/callback?code=...&state=...
```

`quarkus-web`:

1. Validates `state`.
2. Exchanges code for access token and refresh token.
3. Verifies granted scopes include `youtube.force-ssl`.
4. Stores encrypted refresh token.
5. Fetches Google identity metadata if needed.
6. Starts channel discovery.
7. Stores selected integration state for `quarkus-adapter` workers.

### 4) Channel Discovery

Call YouTube:

```text
channels.list(part=snippet,contentDetails&mine=true)
```

Show tenant:

- Channel title
- Channel ID
- Thumbnail
- Upload playlist ID

Tenant selects one or more channels.

### 5) Activation

Create `youtube_channels_by_tenant` rows:

- `tenant_id`
- `channel_id`
- `connection_id`
- `poll_enabled=true`
- `high_watermark=now() - safety_window`

Immediately run a small backfill/poll job and show status:

- `Connected`
- `Polling`
- `Last successful sync`
- `Last error`

## Polling Model

Replace the single-channel scheduled poller with a tenant-aware poller.

Pseudo-flow:

```text
every N seconds:
  find active youtube channel connections due for polling
  for each connection:
    load encrypted refresh token
    decrypt token
    refresh access token
    call commentThreads.list(allThreadsRelatedToChannelId=channelId, order=time)
    publish canonical inbound events
    update watermark/status
```

Use both:

- `commentId` idempotency key
- timestamp high watermark

Keep a small overlap window, for example 5 to 15 minutes, because APIs can return late or edited comments.

## Quota Control

Add per-tenant and global quota controls:

- Default poll interval per channel.
- Backoff after empty polls.
- Faster polling immediately after connect.
- Circuit breaker on repeated 403/429/5xx.
- Daily per-tenant quota budget.
- Global API quota dashboard.

## Project and API Enablement Strategy

### Hosted Mode

Enable once during deployment:

```bash
gcloud services enable youtube.googleapis.com --project=<app-google-cloud-project>
```

Also keep enabled:

```bash
gcloud services enable serviceusage.googleapis.com --project=<app-google-cloud-project>
```

The runtime does not need to enable YouTube per tenant in hosted mode.

### BYO Project Mode

If a tenant insists on their own project:

1. Ask for project ID or number.
2. Check whether `youtube.googleapis.com` is enabled.
3. If not enabled, call Service Usage `services.enable`.
4. Poll the returned long-running operation.
5. Continue OAuth setup only after service enablement is complete.

Required IAM permission:

```text
serviceusage.services.enable
```

Predefined role:

```text
roles/serviceusage.serviceUsageAdmin
```

Avoid requiring `Owner`. It is too broad.

## Security Requirements

- Store refresh tokens encrypted at rest.
- Use envelope encryption with KMS in production.
- Separate tenant data by `tenantId`.
- Never log access tokens, refresh tokens, auth codes, OAuth state payloads, or client secrets.
- Use short-lived OAuth `state` records.
- Bind OAuth `state` to authenticated app user and tenant.
- Validate redirect URIs exactly.
- Support disconnect/revoke.
- Store granted scopes and require re-consent when required scopes change.
- Add audit events for connect, disconnect, token refresh failures, channel selection, polling enablement, and approvals.

## Tenant UX

Target UX:

1. `Connect YouTube`
2. Google consent
3. Select channel
4. `Start syncing`

Show clear status:

- `Connected`
- `Waiting for first sync`
- `Syncing comments`
- `Needs reconnect`
- `YouTube API disabled`
- `Insufficient channel permissions`
- `Quota limited`

Do not expose raw Google errors to tenants. Map them:

| Google error | Tenant-facing message | Operator action |
| --- | --- | --- |
| `SERVICE_DISABLED` | YouTube API is not enabled for the app project. | Enable `youtube.googleapis.com`. |
| `ACCESS_TOKEN_SCOPE_INSUFFICIENT` | YouTube needs to be reconnected. | Force re-consent with `youtube.force-ssl`. |
| `invalid_grant` | Google connection expired or was revoked. | Ask tenant to reconnect. |
| `quotaExceeded` | YouTube sync is temporarily rate-limited. | Backoff and monitor quota. |
| `channelNotFound` | The selected channel no longer exists or is unavailable. | Ask tenant to reconnect/select channel. |
| `forbidden` | The connected Google account cannot access these comments. | Ask tenant to reconnect with a channel owner/manager account. |

## Local Development Strategy

### Mock Mode

No Google credentials:

```bash
GOOGLE_YOUTUBE_POLL_ENABLED=false
```

Use:

```text
/mock/google/youtube-comment
```

### Single-Credential Dev Mode

Current mode remains useful for debugging one channel:

```bash
GOOGLE_CREDENTIALS_PATH=/path/to/google-credentials.json
GOOGLE_YOUTUBE_POLL_ENABLED=true
GOOGLE_YOUTUBE_CHANNEL_ID=<channel-id>
GOOGLE_YOUTUBE_SCOPE=https://www.googleapis.com/auth/youtube.force-ssl
```

### Multi-Tenant OAuth Dev Mode

Use a local OAuth client and callback:

```bash
GOOGLE_OAUTH_CLIENT_ID=<client-id>
GOOGLE_OAUTH_CLIENT_SECRET=<client-secret>
GOOGLE_OAUTH_REDIRECT_URI=http://localhost:8081/integrations/google/oauth/callback
```

Store encrypted tokens in local Cassandra or a local file only for development.

## Migration Plan

Do not split immediately just to split. Split at the OAuth/tenant-management milestone.

### Step 1: Stabilize Current Adapter

- Keep current `quarkus-adapter` running the local event stack.
- Stop adding long-lived user-facing UI features to it.
- Keep mock endpoints for local development.

### Step 2: Extract Shared Contracts

Create:

```text
quarkus-common/
  CanonicalInboundEvent.java
  NewsSummaryGeneratedEvent.java
  Google/YouTube DTOs
  tenant identifiers
  validation helpers
```

Both `quarkus-adapter` and `quarkus-web` depend on this module.

### Step 3: Create `quarkus-web`

Move or add:

- Tenant management.
- User/session auth.
- Google OAuth connect/callback.
- Channel picker.
- Integration settings.
- Dashboard/read APIs.
- Approval queue and audit UI.

### Step 4: Keep Adapter Worker-Focused

Leave in `quarkus-adapter`:

- Kafka stream producers/consumers.
- Pollers/sync jobs.
- Google Data API sync workers.
- Guardian/HackerNews inbound adapters.
- Canonical event publishing.
- Workflow event consumption.

### Step 5: Define Adapter/Web Coordination

Use one of these patterns:

- Shared database/read model for tenant integration records.
- Kafka command events from web to adapter, for example `IntegrationConnected`, `YouTubeChannelEnabled`, `YouTubeActionApproved`.
- Internal REST only for operational commands that need request/response semantics.

Prefer events for state changes and REST for read/query or explicit admin operations.

## Recommended Next Implementation Step

Implement the hosted OAuth app path first:

1. Create `quarkus-common`.
2. Create `quarkus-web`.
3. Add OAuth config to `quarkus-web`.
4. Add connect/callback endpoints to `quarkus-web`.
5. Store encrypted refresh tokens and selected channels.
6. Let `quarkus-adapter` read active tenant-channel records.
7. Replace the single YouTube poller with a tenant-channel loop.

Do not start with BYO Google Cloud project automation. It adds IAM, billing, service enablement, and support complexity without improving the normal tenant onboarding experience.
