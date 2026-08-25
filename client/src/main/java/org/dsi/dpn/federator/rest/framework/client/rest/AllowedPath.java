// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.client.rest;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;

/**
 * One entry from a REST product's allowed-path configuration: the HTTP method a
 * consumer may use, and the path pattern it may use it on.
 *
 * <p>DSM supplies these in the product's {@code topic} field as a JSON array:
 * <pre>
 *   [{"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"},
 *    {"request_type":"POST","request_path":"/api/v1/fmar/assets"}]
 * </pre>
 *
 * <p>Paths are matched with {@link AntPathMatcher}, so both URI template
 * variables ({@code {mpan}} — one path segment) and Ant wildcards
 * ({@code *}, {@code **}) work.
 */
@Slf4j
public record AllowedPath(String requestType, String requestPath) {

    private static final String FIELD_REQUEST_TYPE = "request_type";
    private static final String FIELD_REQUEST_PATH = "request_path";

    /**
     * Lenient reader: DSM configuration is hand-edited, so unquoted field names,
     * single quotes and trailing commas are accepted rather than failing the
     * whole product.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /**
     * Last-resort extraction for entries too malformed to parse as JSON (for
     * example {@code {request_type:GET request_path:/x}} — no comma between the
     * fields). Pulls the two field values out positionally.
     */
    private static final Pattern LOOSE_ENTRY = Pattern.compile(
            "request_type\\s*[:=]\\s*[\"']?(?<type>[A-Za-z]+)[\"']?"
                    + "[\\s,]*"
                    + "request_path\\s*[:=]\\s*[\"']?(?<path>[^\"',\\s]+)[\"']?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses a product's allowed-path configuration.
     *
     * <p>Accepts the JSON array form above (with or without the enclosing
     * brackets), and falls back to the earlier comma-separated
     * {@code METHOD:path} / bare-path form so existing product configuration
     * keeps working.
     *
     * @return the parsed entries, or an empty list if nothing usable was found
     *         (an empty list denies everything — fail closed)
     */
    public static List<AllowedPath> parse(String topic) {
        if (topic == null || topic.isBlank()) return Collections.emptyList();

        String trimmed = topic.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            List<AllowedPath> parsed = parseJson(trimmed);
            // JSON-shaped but unparseable: try the loose form before giving up.
            return parsed.isEmpty() ? parseLoose(trimmed) : parsed;
        }
        return parseLegacy(trimmed);
    }

    private static List<AllowedPath> parseJson(String topic) {
        // A bare "{...},{...}" sequence is not a JSON document — wrap it.
        String json = topic.startsWith("[") ? topic : "[" + topic + "]";
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return Collections.emptyList();

            List<AllowedPath> entries = new ArrayList<>();
            for (JsonNode node : root) {
                String type = text(node, FIELD_REQUEST_TYPE);
                String path = text(node, FIELD_REQUEST_PATH);
                if (isBlank(type) || isBlank(path)) {
                    log.warn("Ignoring allowed-path entry missing {} or {}: {}",
                            FIELD_REQUEST_TYPE, FIELD_REQUEST_PATH, node);
                    continue;
                }
                entries.add(new AllowedPath(type.trim(), path.trim()));
            }
            return entries;
        } catch (Exception e) {
            log.debug("Allowed-path config is not valid JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<AllowedPath> parseLoose(String topic) {
        List<AllowedPath> entries = new ArrayList<>();
        Matcher matcher = LOOSE_ENTRY.matcher(topic);
        while (matcher.find()) {
            entries.add(new AllowedPath(
                    matcher.group("type"), trimEntryBrace(matcher.group("path"))));
        }
        if (entries.isEmpty()) {
            log.warn("Could not parse any allowed-path entry from product configuration");
        }
        return entries;
    }

    /**
     * Drops the entry's own closing brace from a loosely-parsed path.
     *
     * <p>The path may legitimately contain braces ({@code /assets/{mpan}}), so the
     * pattern above cannot simply exclude them. Instead, trailing braces are
     * removed only while they are unbalanced — i.e. they closed the surrounding
     * entry rather than a template variable.
     */
    private static String trimEntryBrace(String path) {
        String result = path;
        while (result.endsWith("}") && count(result, '}') > count(result, '{')) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static long count(String value, char c) {
        return value.chars().filter(ch -> ch == c).count();
    }

    /**
     * Earlier format: comma-separated {@code METHOD:antPattern} entries, or bare
     * Ant patterns that permit any method (represented here as {@code *}).
     */
    private static List<AllowedPath> parseLegacy(String topic) {
        List<AllowedPath> entries = new ArrayList<>();
        for (String entry : topic.split(",")) {
            String candidate = entry.trim();
            if (candidate.isEmpty()) continue;

            int colonIdx = candidate.indexOf(':');
            if (colonIdx > 0 && !candidate.startsWith("/")) {
                entries.add(new AllowedPath(
                        candidate.substring(0, colonIdx).trim(),
                        candidate.substring(colonIdx + 1).trim()));
            } else {
                entries.add(new AllowedPath("*", candidate));
            }
        }
        return entries;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    /**
     * Whether this entry permits the given request.
     *
     * @param method  request method; {@code null} skips the method check and
     *                compares the path only
     * @param path    request path, with any query string already removed
     */
    public boolean matches(AntPathMatcher matcher, String method, String path) {
        boolean methodOk = "*".equals(requestType)
                || method == null
                || requestType.equalsIgnoreCase(method);
        return methodOk && matcher.match(requestPath, path);
    }
}
