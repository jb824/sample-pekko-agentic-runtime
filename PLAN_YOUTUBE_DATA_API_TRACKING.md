# Plan: YouTube Data API Tracking, Demo Capabilities, and Pekko Agents

## Goal

Build a demo that shows potential users how the app can help with:

- Building an audience.
- Generating client leads.
- Increasing viewership.
- Increasing subscribers.
- Managing channel presence and content operations.
- Moving beyond descriptive analytics into diagnostic, predictive, and prescriptive assistance.

This document covers YouTube data/capability coverage plus Pekko LLM-backed agentic workflows for turning that data into decisions and actions.

## Important Boundary

The YouTube Data API is necessary, but it is not sufficient for the full growth story.

Use the YouTube Data API for:

- Channel metadata.
- Video inventory and metadata.
- Playlists and channel layout.
- Comments and replies.
- Captions.
- Thumbnails.
- Watermarks.
- Search/discovery.
- Activities.
- Membership metadata.
- Basic public statistics exposed on channel/video resources.

Use the YouTube Analytics API later for:

- Views over time.
- Watch time.
- Average view duration.
- Audience retention-style reporting.
- Subscribers gained/lost.
- Traffic sources.
- Playback locations.
- Device/region breakdowns.
- Revenue/ad metrics where available.

References:

- YouTube Data API reference: https://developers.google.com/youtube/v3/docs
- YouTube Analytics metrics: https://developers.google.com/youtube/analytics/metrics
- YouTube Analytics dimensions: https://developers.google.com/youtube/analytics/dimensions

## Current Tracking

Current implementation tracks only a narrow YouTube slice:

- Source: `youtube`
- Event type: `YouTubeCommentReceived`
- API source: `commentThreads.list`
- Payload: channel ID, video ID, comment ID, author display name, comment text, published time, like count.
- Tenant routing: currently process-configured via env vars, not yet tenant-channel records.

That is enough to prove the Kafka -> Pekko -> Cassandra path, but not enough for a compelling creator/business demo.

## Demo Product Shape

The demo should feel like a YouTube growth cockpit:

1. `Connect YouTube`
2. Discover owned channels.
3. Build a channel profile.
4. Inventory videos/playlists/comments.
5. Track audience signals.
6. Surface lead opportunities from comments.
7. Suggest content and channel optimizations.
8. Use Pekko agents for diagnostic, predictive, and prescriptive assistance.
9. Optionally execute safe channel-management actions after user approval.

The product should infer likely user needs:

- "Which videos are attracting prospects?"
- "Which comments are buying signals?"
- "Which videos need better titles, thumbnails, captions, or descriptions?"
- "What topics are my competitors ranking for?"
- "What regions/languages should I target?"
- "Which subscribers/members need attention?"
- "What channel layout changes would increase conversions?"

## Analytics Maturity Model

| Layer | Question answered | Demo promise | Example output |
| --- | --- | --- | --- |
| Descriptive | What happened? | "Here is your channel/content/comment/search state." | Recent videos, comment volume, missing captions, public search rankings. |
| Diagnostic | Why did it happen? | "Here is why a video, topic, or funnel is underperforming." | Low views correlate with weak packaging, missing playlists, poor metadata, or topic mismatch. |
| Predictive | What is likely to happen? | "Here is what will probably grow, stall, or produce leads." | This video is likely to attract leads; this topic is likely to outperform; this channel is at risk of stale growth. |
| Prescriptive | What should I do next? | "Here are prioritized actions and one-click fixes." | Reply to these leads, update these titles, create these playlists, test these thumbnails, localize these videos. |

For the first demo, descriptive data should be treated as raw material, not the final product. The compelling value is in the diagnostic and prescriptive layers.

## Capability Map

| API area | Methods | What to track/store | Demo value |
| --- | --- | --- | --- |
| `activities` | `list` | Channel actions: uploads, likes, favorites, shares where available. | Timeline of creator/channel activity; detect publishing cadence and content patterns. |
| `captions` | `list`, `download`, `insert`, `update`, `delete` | Caption tracks, language, draft status, downloadable transcript text where authorized. | SEO/accessibility audit; transcript-based lead/topic extraction; multilingual expansion. |
| `channelBanners` | `insert` | Uploaded banner URL and update history. | Channel branding improvement demo. |
| `channels` | `list`, `update` | Channel title, description, handle/custom URL where available, country, thumbnails, statistics, branding settings, content details, topic details. | Core channel profile; baseline subscribers/views/video count; branding audit. |
| `channelSections` | `list`, `insert`, `update`, `delete` | Channel shelves/sections, section type, playlist/channel references, ordering. | Channel homepage conversion optimization. |
| `comments` | `list`, `insert`, `update`, `setModerationStatus`, `delete` | Replies, moderation state, author, text, like count, parent comment, timestamps. | Lead detection, reply queue, moderation queue, customer support triage. |
| `commentThreads` | `list`, `insert` | Top-level comments, replies summary, video/channel association, moderation status, timestamps. | Primary engagement inbox and prospect discovery. |
| `i18nLanguages` | `list` | Supported UI/language codes. | Language targeting and localization planning. |
| `i18nRegions` | `list` | Supported region codes. | Region-specific search/content planning. |
| `members` | `list` | Active members, level access, member channel IDs, membership details where available. | Community/revenue segment tracking for membership-enabled channels. |
| `membershipsLevels` | `list` | Membership levels, names, pricing/level metadata where available. | Member segmentation and premium-community demo. |
| `playlistImages` | `list`, `insert`, `update`, `delete` | Playlist image metadata and custom artwork state. | Playlist branding and packaging audit. |
| `playlistItems` | `list`, `insert`, `update`, `delete` | Video ordering inside playlists, upload playlist inventory, playlist-specific metadata. | Content library, series organization, funnel playlists. |
| `playlists` | `list`, `insert`, `update`, `delete` | Playlist title, description, privacy, item count, thumbnails. | Content hubs, lead-nurture playlists, topic clusters. |
| `search` | `list` | Search results for keywords, competitor channels/videos/playlists, rank snapshots by region/language. | Market research, competitor discovery, topic opportunity finder. |
| `subscriptions` | `list`, `insert`, `delete` | Channels the authenticated user/channel subscribes to, subscription metadata. | Ecosystem mapping; competitor/partner tracking. Be careful with write actions. |
| `thumbnails` | `set` | Custom thumbnail upload/update events; thumbnail URLs from video/channel resources. | Thumbnail optimization demo and before/after packaging workflow. |
| `videoAbuseReportReasons` | `list` | Reference data only. | Mostly admin/moderation reference; low demo value. |
| `videoCategories` | `list` | Category IDs/names per region. | Correct category selection and market research. |
| `videos` | `list`, `insert`, `update`, `rate`, `getRating`, `reportAbuse`, `delete` | Video metadata, status, statistics, content details, recording details, topic details, processing status, tags, category, localization. | Content audit, publishing checklist, metadata optimization, inventory and growth analysis. |
| `watermarks` | `set`, `unset` | Channel watermark state/update events. | Subscriber CTA branding demo. |

## Recommended Demo Modules

### 1) Channel Snapshot

Data API sources:

- `channels.list`
- `playlistItems.list` for uploads playlist
- `videos.list`

Track:

- Channel title/description/country.
- Subscriber count if available.
- View count.
- Video count.
- Branding thumbnails.
- Upload playlist ID.
- Recent videos.
- Video status and visibility.

Demo output:

- "Your channel at a glance."
- "Top metadata issues."
- "Content cadence."
- "Missing descriptions/tags/thumbnails/captions."

### 2) Content Inventory

Data API sources:

- `channels.list(contentDetails)`
- `playlistItems.list(uploads playlist)`
- `videos.list`
- `captions.list`

Track per video:

- Title.
- Description.
- Tags.
- Category.
- Publish date.
- Duration.
- Privacy/status.
- View/like/comment counts where available.
- Thumbnail URLs.
- Caption tracks.
- Localization fields.

Demo output:

- Searchable video library.
- Packaging score.
- SEO readiness score.
- Caption/localization gaps.

### 3) Comment and Lead Inbox

Data API sources:

- `commentThreads.list`
- `comments.list`
- `comments.insert`
- `comments.setModerationStatus`

Track:

- Top-level comments.
- Replies.
- Author display name/channel ID.
- Video association.
- Comment text.
- Published/updated time.
- Like count.
- Moderation status where available.

Demo output:

- Lead-like comments.
- Questions needing response.
- Complaints/support issues.
- Collaboration inquiries.
- Testimonial candidates.
- Spam/moderation queue.

### 4) Search and Opportunity Research

Data API sources:

- `search.list`
- `videos.list`
- `channels.list`
- `videoCategories.list`
- `i18nRegions.list`
- `i18nLanguages.list`

Track:

- Keyword query.
- Region.
- Language.
- Result rank.
- Result type: video/channel/playlist.
- Video/channel metadata snapshots.
- Competitor channel IDs.

Demo output:

- Topic opportunity board.
- Competitor discovery.
- Regional keyword snapshots.
- "Videos ranking for topics your buyers search."

Note: `search.list` is quota-expensive compared with many list calls, so cache aggressively and make it an on-demand demo feature.

### 5) Playlist and Channel Layout Optimizer

Data API sources:

- `playlists.list`
- `playlistItems.list`
- `channelSections.list`
- `playlistImages.list`

Track:

- Playlists.
- Playlist ordering.
- Channel shelves.
- Featured playlists.
- Playlist artwork.

Demo output:

- "Your channel homepage is missing a lead-gen playlist."
- "Create a Start Here playlist."
- "Move case studies higher."
- "Group related videos into a funnel."

### 6) Branding and Conversion Assets

Data API sources:

- `channels.list(brandingSettings)`
- `channels.update`
- `channelBanners.insert`
- `thumbnails.set`
- `watermarks.set`
- `watermarks.unset`

Track:

- Banner state.
- Thumbnail state.
- Watermark updates.
- Branding settings.

Demo output:

- Channel branding checklist.
- Thumbnail refresh candidates.
- Subscriber watermark CTA setup.

Treat these as approval-gated write actions. A demo can show recommendations first and require explicit user confirmation before API writes.

### 7) Members and Community Revenue

Data API sources:

- `members.list`
- `membershipsLevels.list`

Track:

- Membership levels.
- Active members.
- New/updated member pages where available.
- Member channel IDs.
- Level access.

Demo output:

- Member segmentation.
- "High-value community members to engage."
- Membership tier health.

Constraints:

- Requires `https://www.googleapis.com/auth/youtube.channel-memberships.creator`.
- Only works for eligible membership-enabled creator channels.

### 8) Publishing and Metadata Management

Data API sources:

- `videos.insert`
- `videos.update`
- `thumbnails.set`
- `captions.insert/update/delete`
- `playlists.insert/update/delete`
- `playlistItems.insert/update/delete`

Track:

- Draft/published video metadata.
- Upload status.
- Thumbnail status.
- Caption status.
- Playlist placement.

Demo output:

- Publishing checklist.
- Metadata optimizer.
- Localization/caption checklist.
- Playlist distribution checklist.

Constraints:

- Uploaded videos from unverified API projects can be restricted to private viewing mode. Plan for Google API compliance/audit before promising upload automation.
- Write actions should be disabled by default in demo unless the user explicitly opts in.

## Pekko Agent Runtime Additions

Do not add YouTube API tools to the Pekko runtime for the default architecture. Quarkus owns YouTube API access through webhooks, pollers, OAuth credentials, watermarks, and read models. Kafka carries canonical YouTube events and analysis requests into Pekko.

Pekko should consume event/message context and produce analysis events:

```text
Quarkus adapter:
  YouTube API polling/webhooks -> canonical events -> Kafka

Pekko runtime:
  Kafka events -> LLM-backed analysis/forecasting/cause-effect workflows -> Kafka analysis events

Quarkus web:
  dashboards, approvals, tenant UX, action queue
```

Add event types for YouTube analysis inputs:

- `YouTubeChannelSnapshotCaptured`
- `YouTubeVideoSnapshotCaptured`
- `YouTubeCommentThreadCaptured`
- `YouTubeCommentReplyCaptured`
- `YouTubePlaylistSnapshotCaptured`
- `YouTubeSearchSnapshotCaptured`
- `YouTubeMembershipSnapshotCaptured`
- `YouTubeAnalyticsSnapshotCaptured`
- `YouTubeBrandingSnapshotCaptured`
- `YouTubeActionApproved`

Add event types for Pekko analysis outputs:

- `YouTubeLeadSignalsDetected`
- `YouTubeAudienceDiagnosisGenerated`
- `YouTubeContentOpportunityGenerated`
- `YouTubeForecastGenerated`
- `YouTubeCauseEffectAnalysisGenerated`
- `YouTubeBrandActionPlanGenerated`
- `YouTubeReplyDraftGenerated`
- `YouTubeOptimizationRecommendationGenerated`
- `YouTubeApprovedActionPrepared`

Add agent roles in the Pekko runtime. These are not API clients; they are event/read-model analysts:

- `AudienceGrowthAgent`
  - Goal: identify event patterns and channel/content improvements that can increase views and subscribers.
  - Inputs: channel snapshots, video snapshots, search snapshots, analytics snapshots, playlist snapshots.
  - Outputs: audience diagnosis, growth blockers, content opportunities, subscriber/viewership action plans.

- `LeadGenerationAgent`
  - Goal: find and prioritize comments/viewers that look like business opportunities.
  - Inputs: comment thread events, reply events, video metadata, historical lead labels.
  - Outputs: lead signal events, reply draft events, lead follow-up recommendations.

- `ContentStrategistAgent`
  - Goal: recommend what to publish next and how to package it.
  - Inputs: video inventory snapshots, search snapshots, comment questions, competitor/search result snapshots.
  - Outputs: topic opportunity events, packaging recommendations, publishing briefs.

- `ChannelOptimizerAgent`
  - Goal: diagnose channel layout, playlists, branding, watermark, thumbnails, captions, and localization gaps.
  - Inputs: channel snapshots, branding snapshots, playlist snapshots, captions/localization snapshots.
  - Outputs: channel optimization plans and approval-gated action proposals.

- `CommunityManagerAgent`
  - Goal: prioritize replies, moderation, member engagement, and community follow-up.
  - Inputs: comment/reply events, membership snapshots, unanswered-comment state, moderation state.
  - Outputs: reply drafts, moderation recommendations, member engagement recommendations.

- `ForecastingAgent`
  - Goal: estimate likely outcomes and risk using historical snapshots.
  - Inputs: time-series snapshots for views, subscribers, comments, publish cadence, search rank, and analytics metrics when available.
  - Outputs: forecast events with confidence, assumptions, leading indicators, and recommended interventions.

- `CauseEffectAgent`
  - Goal: produce causal hypotheses, not false certainty.
  - Inputs: before/after snapshots, action logs, content metadata changes, publishing cadence, comments, analytics time series.
  - Outputs: cause-effect analysis with evidence strength, alternative explanations, and suggested experiments.

Add Pekko workflows:

- `youtube-lead-finder`
  - Trigger: batch of comment events or explicit web request.
  - Inputs: tenant ID, channel ID, recent comments, video context, optional topic/video filters.
  - Output: ranked lead-like comments, evidence, drafted replies, and safe next actions.

- `youtube-audience-diagnosis`
  - Trigger: scheduled digest, channel snapshot update, or explicit web request.
  - Inputs: tenant ID, channel ID, channel/video/playlist/comment/search snapshots.
  - Output: diagnostic report explaining likely growth blockers across content, comments, playlists, metadata, captions, and search positioning.

- `youtube-content-opportunity`
  - Trigger: new search snapshot, recurring planning cadence, or explicit web request.
  - Inputs: tenant ID, channel ID, target buyer/topic/region, search snapshots, existing inventory, comment questions.
  - Output: recommended video topics, competitor examples, search evidence, and suggested titles/descriptions.

- `youtube-channel-optimization`
  - Trigger: channel/branding/playlist snapshot update or explicit web request.
  - Inputs: tenant ID, channel ID, branding snapshots, playlist snapshots, video inventory.
  - Output: prioritized channel fixes: playlists, sections, branding, thumbnails, watermark, captions, localization.

- `youtube-forecast`
  - Trigger: scheduled digest or enough new metrics snapshots.
  - Inputs: tenant ID, channel ID, historical snapshots, analytics metrics when available.
  - Output: forecast for audience growth, viewership, comment volume, lead volume, and subscriber movement with confidence and assumptions.

- `youtube-cause-effect-analysis`
  - Trigger: approved action completed, anomaly detected, or explicit web request.
  - Inputs: before/after snapshots, action log, video/channel changes, analytics windows.
  - Output: likely causes, evidence strength, alternative explanations, and next experiment.

- `youtube-action-plan`
  - Inputs: one or more findings from the workflows above.
  - Output: ordered action plan with effort, expected impact, risk, and API write actions that require user approval.

Suggested Pekko workflow style:

```text
research:
  good for one-shot channel audits and opportunity reports

react:
  less important here unless Pekko later gets safe internal read-model tools

planner-executor:
  good for multi-stage event analysis such as lead finding -> reply drafting -> action plan

event-driven actor workflow:
  preferred default for YouTube runtime analysis because Kafka supplies bounded input context
```

Pekko should receive bounded context, not fetch YouTube directly. If a workflow needs more data, it should emit a request/command event such as `YouTubeContextRequested`; Quarkus decides whether to query read models, poll Google, or reject the request.

## Example Agentic Demo Workflows

### Workflow 1: Find Client Leads in My Comments

Goal:

- Generate client leads from existing engagement.

Inputs:

- `YouTubeCommentThreadCaptured`
- `YouTubeCommentReplyCaptured`
- `YouTubeVideoSnapshotCaptured`

Process:

1. Pull recent comments.
2. Classify intent: buyer question, support issue, testimonial, collaboration, spam, generic engagement.
3. Score lead potential.
4. Group by video/topic.
5. Draft replies for top leads.

Demo output:

- "12 comments look like potential leads."
- "5 ask about pricing/availability."
- "3 ask for help solving a problem your service addresses."
- "Approve these replies."

### Workflow 2: Why Is This Video Not Growing?

Goal:

- Diagnose video underperformance using available Data API signals.

Inputs:

- `YouTubeVideoSnapshotCaptured`
- `YouTubePlaylistSnapshotCaptured`
- `YouTubeCommentThreadCaptured`
- `YouTubeSearchSnapshotCaptured`
- caption/localization snapshot events

Process:

1. Compare video metadata completeness to better-performing videos.
2. Check caption availability.
3. Check playlist placement.
4. Check topic/search competition.
5. Check comment questions that indicate mismatch or missing information.

Demo output:

- "Likely reasons: weak description, no captions, not in topic playlist, title does not match search phrasing."
- "Recommended fixes ranked by effort/impact."

### Workflow 3: What Should I Make Next?

Goal:

- Recommend content topics that can increase views and subscribers.

Inputs:

- Video inventory snapshot events.
- Comment/question events.
- Search snapshot events for target keywords.
- Competitor channel/video snapshots.
- Region/language/category reference snapshots.

Process:

1. Extract recurring questions from comments.
2. Compare against existing video inventory.
3. Search YouTube for related topics.
4. Identify competitor videos and gaps.
5. Rank topics by demand, fit, and lead potential.

Demo output:

- "Make these 5 videos next."
- "Topic A is requested in comments and has competitor demand."
- "Topic B is underserved in your inventory."

### Workflow 4: Turn My Channel Homepage Into a Funnel

Goal:

- Convert casual viewers into subscribers/leads.

Inputs:

- `YouTubeChannelSnapshotCaptured`
- `YouTubePlaylistSnapshotCaptured`
- `YouTubeVideoSnapshotCaptured`

Process:

1. Detect existing channel sections.
2. Identify content clusters.
3. Identify case studies/testimonials/how-to videos.
4. Recommend homepage sections and playlist order.

Demo output:

- "Create a Start Here playlist."
- "Move case studies above uploads."
- "Group beginner tutorials into a conversion path."

### Workflow 5: Subscriber CTA and Branding Audit

Goal:

- Improve channel conversion assets.

Inputs:

- `YouTubeBrandingSnapshotCaptured`
- `YouTubeChannelSnapshotCaptured`
- `YouTubeVideoSnapshotCaptured`

Process:

1. Check banner, watermark, thumbnails, channel description.
2. Identify missing or stale branding assets.
3. Recommend updates.

Demo output:

- "No subscriber watermark detected."
- "Channel banner does not mention your lead magnet."
- "These 8 videos need thumbnail refresh."

### Workflow 6: Regional Expansion Finder

Goal:

- Find region/language opportunities.

Inputs:

- `YouTubeSearchSnapshotCaptured`
- region/language reference snapshot events
- caption/localization snapshot events

Process:

1. Run target queries across selected regions/languages.
2. Compare competitor density.
3. Check existing caption/localization coverage.
4. Recommend regions/languages to test.

Demo output:

- "Spanish captions are a high-leverage next step for these videos."
- "Canada and UK search results show lower competition for your topic."

## Read vs Write Capability Levels

Use progressive consent. Do not ask for every scope up front.

### Level 1: Public Research

Purpose:

- Competitor research.
- Search.
- Public video/channel snapshots.
- Regions/languages/categories.

Auth:

- API key can work for many public reads.
- OAuth optional.

Features:

- `search.list`
- `videos.list`
- `channels.list`
- `playlists.list`
- `playlistItems.list`
- `i18nRegions.list`
- `i18nLanguages.list`
- `videoCategories.list`

### Level 2: Connected Channel Read

Purpose:

- Owned channel inventory.
- Private/authorized channel views where available.
- Comment ingestion.

Auth:

- OAuth.

Likely scopes:

- `https://www.googleapis.com/auth/youtube.readonly`
- `https://www.googleapis.com/auth/youtube.force-ssl` for comments and broader comment/caption operations.

Features:

- Channel inventory.
- Upload playlist sync.
- Video metadata sync.
- Comments/commentThreads.
- Captions list/download where authorized.

### Level 3: Community and Membership Read

Purpose:

- Member/community intelligence.

Auth:

- OAuth.

Scope:

- `https://www.googleapis.com/auth/youtube.channel-memberships.creator`

Features:

- `members.list`
- `membershipsLevels.list`

### Level 4: Channel Management Writes

Purpose:

- Act on recommendations.

Auth:

- OAuth with explicit user opt-in.

Likely scopes:

- `https://www.googleapis.com/auth/youtube.force-ssl`
- `https://www.googleapis.com/auth/youtube.upload` if uploading videos.

Features:

- Reply to comments.
- Moderate comments.
- Update video metadata.
- Upload/set thumbnails.
- Manage captions.
- Manage playlists.
- Manage channel sections.
- Set/unset watermark.
- Update channel branding.

## Data Model Additions

Add tables/collections around these entities:

- `youtube_channels`
- `youtube_videos`
- `youtube_video_snapshots`
- `youtube_comments`
- `youtube_comment_threads`
- `youtube_comment_replies`
- `youtube_playlists`
- `youtube_playlist_items`
- `youtube_channel_sections`
- `youtube_captions`
- `youtube_search_queries`
- `youtube_search_results`
- `youtube_regions`
- `youtube_languages`
- `youtube_video_categories`
- `youtube_members`
- `youtube_membership_levels`
- `youtube_branding_assets`
- `youtube_action_log`

Store raw API payload snapshots separately from normalized fields when possible:

- Normalized fields power UI and queries.
- Raw JSON helps with debugging and avoids re-ingesting when fields are later added.

## Demo Metrics to Show

From YouTube Data API:

- Subscriber count snapshot.
- Total channel views snapshot.
- Total video count snapshot.
- Per-video view count snapshot.
- Per-video like count snapshot.
- Per-video comment count snapshot.
- Comment volume.
- Reply volume.
- Unanswered questions.
- Lead-like comments.
- Videos missing captions.
- Videos missing strong descriptions.
- Videos missing tags.
- Videos with weak thumbnail coverage.
- Playlist coverage.
- Channel layout completeness.

From YouTube Analytics API later:

- Views over time.
- Watch time.
- Average view duration.
- Subscribers gained/lost.
- Traffic sources.
- Geography.
- Device type.
- Playback location.
- Revenue/ad metrics if authorized.

## Runtime Boundaries

Use three clear runtime boundaries:

```text
quarkus-adapter:
  external API sync, event adapters, Kafka streams, canonical event publishing

quarkus-web:
  tenants, users, OAuth, dashboards, integration settings, approval queue

pekko runtime:
  LLM-backed event analysis, diagnostic/predictive/prescriptive assistance
```

`quarkus-web` should own:

- User auth/session.
- Tenant/account management.
- OAuth connect/callback.
- Channel picker.
- Integration settings.
- Dashboards and read APIs.
- Approval queue for proposed YouTube write actions.

`quarkus-adapter` should own:

- Google/YouTube sync workers.
- Kafka producers/consumers.
- Canonical inbound event production.
- Workflow event consumption.
- Watermarks/checkpoints.
- Source-specific API error handling.

Shared contracts should live in `quarkus-common`:

```text
quarkus-common/
  canonical events
  YouTube DTOs
  tenant identifiers
  integration status contracts
  action approval/event contracts
```

Do not put LLM-backed diagnostic/predictive/prescriptive logic in Quarkus. Quarkus should answer "what data do we have?" and "what external API operation is safe?" Pekko agents should answer "what does this event/history mean, what is likely to happen, what caused it, and what should the user do?"

## Implementation Order

### Phase 1: Descriptive Read-Only Demo

Build:

- Channel snapshot.
- Video inventory.
- Playlist inventory.
- Comment/lead inbox.
- Search opportunity board.
- Regions/languages/categories reference sync.

Why:

- Required data foundation.
- Lower OAuth risk.
- No destructive actions.

### Phase 2: Diagnostic Workflows

Build in Pekko:

- Video underperformance diagnosis.
- Comment-to-lead diagnosis.
- Topic gap diagnosis.
- Packaging diagnosis.
- Playlist/funnel diagnosis.
- Localization diagnosis.

Why:

- Moves demo beyond dashboards.
- Gives users explanations they can evaluate.
- Mostly read-only.

### Phase 3: Predictive Workflows

Build in Pekko:

- Lead potential predictor.
- Topic opportunity predictor.
- Optimization impact predictor.
- Subscriber growth risk predictor.
- Comment escalation predictor.

Why:

- Creates the "what should I pay attention to next?" experience.
- Can start heuristic and improve with YouTube Analytics API history.

### Phase 4: Community/Membership Demo

Build:

- Membership levels.
- Members list.
- Member engagement dashboard.

Why:

- Strong value for eligible creators.
- Requires special scope and eligible channels, so keep optional.

### Phase 5: Prescriptive Approval-Gated Actions

Build:

- Reply to comment.
- Mark comments held/spam/published where supported.
- Update video metadata.
- Set thumbnail.
- Manage playlist/playlist items.
- Set watermark.

Why:

- Shows real automation value.
- Must be explicit and reversible where possible.

### Phase 6: Publishing Support

Build:

- Upload video.
- Upload captions.
- Update publishing metadata.

Why:

- Powerful but heavier compliance/verification surface.
- Not required for the first growth demo.

### Phase 7: YouTube Analytics API

Build:

- Growth metrics dashboard.
- Views/watch-time/subscribers gained/lost.
- Traffic source and geography.
- Content performance over time.

Why:

- Needed to prove the actual business outcomes: viewership, subscriber growth, and lead-generation trends.
- Needed to replace heuristic predictions with stronger time-series and cohort evidence.

## Near-Term Repo Change Recommendation

Do not keep expanding `YouTubePoller` directly.

Create a YouTube integration module:

```text
quarkus-adapter/src/main/java/com/example/adapter/inbound/youtube/
  YouTubeClientFactory.java
  YouTubeChannelSyncService.java
  YouTubeVideoSyncService.java
  YouTubePlaylistSyncService.java
  YouTubeCommentSyncService.java
  YouTubeSearchService.java
  YouTubeReferenceDataSyncService.java
  YouTubeMembershipSyncService.java
  YouTubeActionService.java
```

Keep `YouTubePoller` as a scheduler/orchestrator only. Each API area should be testable without running the scheduler.

## Demo Positioning

Position the demo as:

```text
Connect your YouTube channel and get an instant growth cockpit:
comments that look like leads, videos that need optimization,
topics your buyers search for, channel layout gaps, and content
assets that can be improved with one approval.
```

This maps the broad YouTube Data API surface to user outcomes instead of exposing users to raw API concepts.
