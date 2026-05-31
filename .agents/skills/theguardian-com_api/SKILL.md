# Guardian Open Platform API Skill

Use this skill when an agent needs to search, retrieve, or monitor Guardian content through the Guardian Open Platform Content API.

## Purpose

Query `theguardian.com` content safely and consistently for research, monitoring, summarization, enrichment, and downstream agent workflows.

## Requirements

- Guardian Open Platform API key.
- Store the key as an environment variable, for example `GUARDIAN_API_KEY`.
- Never hard-code or log the API key.
- Respect the chosen access tier, rate limits, quota, licensing terms, and commercial-use restrictions.

## Base URL

```text
https://content.guardianapis.com
```

## Common Endpoints

```text
/search                Search content
/tags                  Search tags
/sections              List sections
/editions              List editions
/{content-id}          Fetch a single item by Guardian content id
```

## Common Query Parameters

Use these first:

```text
api-key=<key>
q=<search terms>
section=<section id>
tag=<tag id>
from-date=YYYY-MM-DD
to-date=YYYY-MM-DD
order-by=newest|oldest|relevance
page-size=<number>
page=<number>
show-fields=headline,trailText,bodyText,byline,thumbnail,publication
show-tags=keyword,contributor
```

Example:

```text
/search?q=climate&section=environment&from-date=2026-01-01&order-by=newest&show-fields=headline,trailText,bodyText,byline,thumbnail&api-key=$GUARDIAN_API_KEY
```

## Pagination Procedure

1. Request page 1 with a bounded `page-size`.
2. Read `response.pages`, `response.currentPage`, and `response.results`.
3. Continue until `currentPage >= pages` or the task limit is reached.
4. Apply a max page cap for agent workflows.

## Agent Usage Pattern

1. Convert user request into a narrow Guardian query.
2. Prefer date ranges and sections to reduce noise.
3. Retrieve only needed fields.
4. Preserve `webTitle`, `webUrl`, `sectionName`, `webPublicationDate`, and `id`.
5. Summarize with attribution and links.
6. Cache or deduplicate by `id`.

## Best Practices

- Use `/search` for discovery and `/{content-id}` for exact retrieval.
- Use `show-fields` only when body text or metadata is needed.
- Use `show-tags` for topic/entity enrichment.
- Treat results as copyrighted content; summarize instead of reproducing full articles.
- Back off on errors and rate limits.
- Log request metadata, not full article body or API keys.

## Do Not Do This

- Do not scrape Guardian pages when the API can provide the data.
- Do not request `show-fields=all` by default.
- Do not fetch unlimited pages.
- Do not expose the API key to clients.
- Do not use developer access for commercial or model-training workflows unless the license permits it.
- Do not assume search results are complete without pagination.

## References

- Guardian Open Platform documentation
- Guardian Open Platform access tiers and terms
- Guardian Content API Explorer
