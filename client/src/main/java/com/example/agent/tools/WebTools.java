package com.example.agent.tools;

import com.example.agent.api.AgentToolResult;
import com.example.agent.api.Tool;
import com.example.agent.api.ToolParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebTools {
    static final int MAX_FETCH_BYTES = 1_000_000;
    static final int MAX_SEARCH_BYTES = 512_000;
    static final int MAX_REDIRECTS = 3;
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern CANONICAL = Pattern.compile("(?is)<link\\s+[^>]*rel\\s*=\\s*['\"]canonical['\"][^>]*>");
    private static final Pattern HREF = Pattern.compile("(?is)href\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style|noscript|template)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENTS = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern BLOCK_TAGS = Pattern.compile("(?is)</?(?:address|article|aside|blockquote|br|dd|div|dl|dt|fieldset|figcaption|figure|footer|form|h[1-6]|header|hr|li|main|nav|ol|p|pre|section|table|tr|td|th|ul)\\b[^>]*>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);?");
    private static final List<String> FETCH_CONTENT_TYPES = List.of(
            "text/html",
            "application/xhtml+xml",
            "text/plain",
            "text/markdown"
    );

    private final WebTransport transport;
    private final HostResolver resolver;

    public WebTools() {
        this(new JavaWebTransport(), InetAddress::getAllByName);
    }

    WebTools(WebTransport transport, HostResolver resolver) {
        this.transport = transport;
        this.resolver = resolver;
    }

    @Tool(
            name = "web.search",
            description = "Searches the web for public facts and returns snippets with source URLs.",
            sourceCapable = true,
            timeoutSeconds = 10,
            params = @ToolParam(name = "query", description = "Search query")
    )
    public CompletionStage<AgentToolResult> search(String query) {
        String normalized = normalizeQuery(query, 180);
        if (normalized.isBlank()) {
            return CompletableFuture.completedFuture(AgentToolResult.failure(
                    new IllegalArgumentException("query must not be blank")));
        }
        URI uri = URI.create("https://api.duckduckgo.com/?q="
                + URLEncoder.encode(normalized, StandardCharsets.UTF_8)
                + "&format=json&no_html=1&skip_disambig=1");
        return validatePublicHttps(uri)
                .thenCompose(validUri -> transport.get(validUri, searchHeaders(), SEARCH_TIMEOUT, MAX_SEARCH_BYTES))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return AgentToolResult.failure(new IllegalStateException("web.search returned HTTP " + response.statusCode()));
                    }
                    return formatSearch(response.body());
                })
                .exceptionally(WebTools::failure);
    }

    @Tool(
            name = "web.fetch",
            description = "Fetches a public HTTPS URL after SSRF checks and returns sanitized readable text. Does not execute JavaScript.",
            sourceCapable = true,
            timeoutSeconds = 15,
            params = @ToolParam(name = "url", description = "Public HTTPS URL to fetch")
    )
    public CompletionStage<AgentToolResult> fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(AgentToolResult.failure(exception));
        }
        return fetch(uri, 0)
                .thenApply(this::formatFetch)
                .exceptionally(WebTools::failure);
    }

    private CompletionStage<WebResponse> fetch(URI uri, int redirects) {
        if (redirects > MAX_REDIRECTS) {
            return CompletableFuture.failedFuture(new IllegalStateException("too many redirects"));
        }
        return validatePublicHttps(uri)
                .thenCompose(validUri -> transport.get(validUri, fetchHeaders(), FETCH_TIMEOUT, MAX_FETCH_BYTES))
                .thenCompose(response -> {
                    Optional<String> location = header(response, "location");
                    if (isRedirect(response.statusCode()) && location.isPresent()) {
                        URI next = response.uri().resolve(location.get());
                        return fetch(next, redirects + 1);
                    }
                    return CompletableFuture.completedFuture(response);
                });
    }

    private CompletionStage<URI> validatePublicHttps(URI input) {
        URI uri = normalizeUri(input);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("only https URLs are allowed"));
        }
        if (uri.getUserInfo() != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("URL user info is not allowed"));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("URL host is required"));
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("only the default HTTPS port is allowed"));
        }
        String host = IDN.toASCII(uri.getHost().trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        URI normalized = URI.create("https://" + host + normalizePort(uri) + normalizePathAndQuery(uri));
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("URL host did not resolve"));
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("URL host resolves to a non-public address"));
                }
            }
            return CompletableFuture.completedFuture(normalized);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private AgentToolResult formatFetch(WebResponse response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return AgentToolResult.failure(new IllegalStateException("web.fetch returned HTTP " + response.statusCode()));
        }
        String contentType = header(response, "content-type").orElse("");
        if (!isFetchContentType(contentType)) {
            return AgentToolResult.failure(new IllegalArgumentException("unsupported content type: " + contentType));
        }
        HtmlMetadata metadata = htmlMetadata(response);
        String text = extractText(response.body(), contentType);
        String output = "URL: " + response.uri()
                + "\nTitle: " + blankSafe(metadata.title(), "<unknown>")
                + "\nCanonical URL: " + blankSafe(metadata.canonicalUrl(), "<unknown>")
                + "\nStatus: " + response.statusCode()
                + "\nContent-Type: " + contentType
                + "\nText:\n" + text;
        return AgentToolResult.success(output, List.of(response.uri().toString()));
    }

    private static HtmlMetadata htmlMetadata(WebResponse response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        String title = "";
        Matcher titleMatcher = TITLE.matcher(body);
        if (titleMatcher.find()) {
            title = compact(titleMatcher.group(1), 240);
        }
        String canonical = "";
        Matcher canonicalMatcher = CANONICAL.matcher(body);
        if (canonicalMatcher.find()) {
            Matcher hrefMatcher = HREF.matcher(canonicalMatcher.group());
            if (hrefMatcher.find()) {
                canonical = safeCanonical(response.uri(), hrefMatcher.group(1));
            }
        }
        return new HtmlMetadata(title, canonical);
    }

    private static String safeCanonical(URI base, String href) {
        try {
            URI uri = base.resolve(href).normalize();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri.toString();
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static String extractText(byte[] body, String contentType) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (isHtmlContentType(contentType)) {
            text = SCRIPT_STYLE.matcher(text).replaceAll(" ");
            text = COMMENTS.matcher(text).replaceAll(" ");
            text = BLOCK_TAGS.matcher(text).replaceAll("\n");
            text = TAGS.matcher(text).replaceAll(" ");
            text = decodeHtmlEntities(text);
        }
        return compact(text, 5000);
    }

    private static boolean isHtmlContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return "text/html".equals(normalized) || "application/xhtml+xml".equals(normalized);
    }

    private static String decodeHtmlEntities(String value) {
        String decoded = value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        Matcher matcher = NUMERIC_ENTITY.matcher(decoded);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String raw = matcher.group(1);
            try {
                int radix = raw.startsWith("x") || raw.startsWith("X") ? 16 : 10;
                String digits = radix == 16 ? raw.substring(1) : raw;
                matcher.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(Integer.parseInt(digits, radix)))));
            } catch (RuntimeException exception) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static AgentToolResult formatSearch(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            List<Resource> resources = new ArrayList<>();
            String abstractText = root.path("AbstractText").asText("");
            String abstractUrl = root.path("AbstractURL").asText("");
            if (!abstractText.isBlank()) {
                resources.add(new Resource(root.path("Heading").asText("DuckDuckGo summary"), abstractText, abstractUrl));
            }
            collectSearch(root.path("Results"), resources, 3);
            collectSearch(root.path("RelatedTopics"), resources, 3);
            return formatResources(resources, "No web search results returned.");
        } catch (Exception exception) {
            return AgentToolResult.failure(exception);
        }
    }

    private static void collectSearch(JsonNode nodes, List<Resource> resources, int maxResults) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            if (resources.size() >= maxResults) {
                return;
            }
            if (node.has("Topics")) {
                collectSearch(node.path("Topics"), resources, maxResults);
            } else {
                String text = node.path("Text").asText("");
                String url = node.path("FirstURL").asText("");
                if (!text.isBlank()) {
                    resources.add(new Resource(truncate(text, 80), text, url));
                }
            }
        }
    }

    private static AgentToolResult formatResources(List<Resource> resources, String emptyMessage) {
        if (resources.isEmpty()) {
            return AgentToolResult.success(emptyMessage);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Resources:");
        for (int index = 0; index < resources.size(); index++) {
            Resource resource = resources.get(index);
            lines.add((index + 1) + ". " + blankSafe(resource.title(), "Untitled"));
            lines.add("   URL: " + blankSafe(resource.url(), "<unknown>"));
            lines.add("   Snippet: " + blankSafe(resource.snippet(), ""));
        }
        return AgentToolResult.success(
                String.join("\n", lines),
                resources.stream()
                        .map(Resource::url)
                        .filter(url -> url != null && !url.isBlank() && !"<unknown>".equals(url))
                        .toList()
        );
    }

    private static URI normalizeUri(URI uri) {
        if (uri == null || uri.toString().isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        return URI.create(uri.toString()).normalize();
    }

    private static String normalizePort(URI uri) {
        return uri.getPort() == -1 ? "" : ":" + uri.getPort();
    }

    private static String normalizePathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return path + query;
    }

    private static boolean isPublicAddress(InetAddress address) {
        return !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress()
                && !isUniqueLocalIpv6(address);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static Optional<String> header(WebResponse response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    private static boolean isFetchContentType(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return FETCH_CONTENT_TYPES.contains(normalized);
    }

    private static Map<String, String> fetchHeaders() {
        return Map.of(
                "User-Agent", "pekko-agent-runtime-webtools/0.1",
                "Accept", "text/html, application/xhtml+xml, text/plain, text/markdown;q=0.8"
        );
    }

    private static Map<String, String> searchHeaders() {
        return Map.of(
                "User-Agent", "pekko-agent-runtime-webtools/0.1",
                "Accept", "application/json"
        );
    }

    private static String normalizeQuery(String query, int maxLength) {
        String normalized = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength).trim();
    }

    private static String compact(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return truncate(normalized, maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private static String blankSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static AgentToolResult failure(Throwable throwable) {
        Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        return AgentToolResult.failure(cause);
    }

    interface HostResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    interface WebTransport {
        CompletionStage<WebResponse> get(URI uri, Map<String, String> headers, Duration timeout, int maxBytes);
    }

    record WebResponse(int statusCode, URI uri, Map<String, List<String>> headers, byte[] body) {
        WebResponse {
            headers = headers == null ? Map.of() : copy(headers);
            body = body == null ? new byte[0] : body.clone();
        }

        private static Map<String, List<String>> copy(Map<String, List<String>> headers) {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            headers.forEach((key, value) -> copied.put(key, value == null ? List.of() : List.copyOf(value)));
            return Map.copyOf(copied);
        }
    }

    private record Resource(String title, String snippet, String url) {
    }

    private record HtmlMetadata(String title, String canonicalUrl) {
    }

    private static final class JavaWebTransport implements WebTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public CompletionStage<WebResponse> get(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .version(HttpClient.Version.HTTP_1_1)
                    .GET();
            headers.forEach(builder::header);
            HttpRequest request = builder.build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(response -> {
                        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
                        if (contentLength > maxBytes) {
                            throw new IllegalStateException("response exceeds maximum size");
                        }
                        try (InputStream stream = response.body()) {
                            return new WebResponse(
                                    response.statusCode(),
                                    response.uri(),
                                    response.headers().map(),
                                    readBounded(stream, maxBytes)
                            );
                        } catch (IOException exception) {
                            throw new IllegalStateException("failed reading response body", exception);
                        }
                    });
        }

        private static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("response exceeds maximum size");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
