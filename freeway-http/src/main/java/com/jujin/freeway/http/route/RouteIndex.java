package com.jujin.freeway.http.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Trie-based HTTP route index.
 * <p>
 * Paths are split by "/" into segments and inserted segment-by-segment into a tree.
 * Supports literal segments, path parameters ({name}), regex-constrained parameters
 * ({name:\\d+}), and wildcards ({path:.*}).
 * <p>
 * Match complexity is O(L) where L is the number of segments in the request path,
 * independent of the total route count.
 */
public final class RouteIndex {

    private static final int MAX_REGEX_LENGTH = PathPattern.MAX_REGEX_LENGTH;

    private final Map<String, TrieNode> methodRoots = new ConcurrentHashMap<>();
    // Fast path: exact match cache for routes without path variables
    private final Map<String, RouteHandler> exactCache = new ConcurrentHashMap<>();

    /**
     * Contribution-consumed routes: both parameter lists are resolved from
     * {@code binder.contribute(...)} extensions when the container builds
     * this class — constructor parameters consume contributions implicitly.
     */
    public RouteIndex(List<Route> routes, List<RouteGroup> groups) {
        // Phase 1: collect all routes
        List<Route> all = new ArrayList<>();
        if (routes != null) all.addAll(routes);
        if (groups != null) {
            for (RouteGroup group : groups) {
                all.addAll(group.expand());
            }
        }
        // Phase 2: insert into trie + exact cache
        for (Route route : all) {
            addRoute(route.method(), route.path(), route.handler());
        }
        // Phase 3: freeze all tries (no further structural changes)
        for (TrieNode root : methodRoots.values()) {
            root.freeze();
        }
    }

    public int routeCount() {
        int count = 0;
        for (TrieNode root : methodRoots.values()) {
            count += countLeaves(root);
        }
        return count;
    }

    private static int countLeaves(TrieNode node) {
        int n = node.handler != null ? 1 : 0;
        if (node.literals != null) {
            for (TrieNode child : node.literals.values()) {
                n += countLeaves(child);
            }
        }
        if (node.paramChildren != null) {
            for (TrieNode child : node.paramChildren) n += countLeaves(child);
        }
        return n;
    }

    private void addRoute(String method, String path, RouteHandler handler) {
        String key = method == null ? "" : method.toUpperCase(Locale.ROOT);
        TrieNode root = methodRoots.computeIfAbsent(key, k -> new TrieNode());
        String[] segments = PathPattern.splitPath(path);
        // Literal segments are stored percent-decoded so registration agrees
        // with matching (requests decode each segment before the trie lookup).
        // An encoded slash (%2F) decodes to '/' but stays inside its original
        // segment — it is never re-split into path structure, so routes whose
        // decoded segments contain '/' are excluded from the exact cache
        // (whose decoded key could otherwise collide with a differently
        // segmented plain request path).
        String[] stored = new String[segments.length];
        boolean hasVariables = false;
        boolean exactCacheable = true;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if ((seg.startsWith("{") && seg.endsWith("}")) || seg.startsWith(":")) {
                hasVariables = true;
                stored[i] = seg;
                continue;
            }
            String decoded = PathPattern.decodeSegment(seg);
            if (decoded == null || decoded.isEmpty()
                    || PathPattern.isPathTraversalSegment(decoded)
                    || PathPattern.containsPathTraversal(decoded)) {
                throw new IllegalArgumentException(
                    "Invalid literal segment '" + seg + "' in route path: " + path);
            }
            if (decoded.contains("/")) {
                exactCacheable = false;
            }
            stored[i] = decoded;
        }
        // Populate exact cache for routes without path variables; the key
        // uses the decoded path so an encoded registration matches a plain
        // request (e.g. /hello%20world ↔ /hello world) on the fast path too.
        if (!hasVariables && exactCacheable) {
            exactCache.put(key + ":" + "/" + String.join("/", stored), handler);
        }
        TrieNode current = root;
        for (int i = 0; i < segments.length; i++) {
            String seg = stored[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                String inner = seg.substring(1, seg.length() - 1);
                String name;
                Pattern regex = null;
                boolean isWildcard = false;
                int colon = inner.indexOf(':');
                if (colon >= 0) {
                    name = inner.substring(0, colon);
                    String regexStr = inner.substring(colon + 1);
                    if (regexStr.length() > MAX_REGEX_LENGTH) {
                        throw new IllegalArgumentException(
                            "Regex constraint too long (max " +
                                MAX_REGEX_LENGTH +
                                " chars): '" +
                                regexStr +
                                "' in path: " +
                                path
                        );
                    }
                    if (".*".equals(regexStr) && i == segments.length - 1) {
                        isWildcard = true;
                    } else {
                        try {
                            regex = Pattern.compile(regexStr);
                        } catch (PatternSyntaxException e) {
                            throw new IllegalArgumentException(
                                "Invalid regex constraint '" +
                                    regexStr +
                                    "' for param '" +
                                    name +
                                    "' in path: " +
                                    path,
                                e
                            );
                        }
                    }
                } else {
                    name = inner;
                }
                current = current.getOrCreateParam(name, regex, isWildcard);
            } else if (seg.startsWith(":") && seg.length() > 1) {
                current = current.getOrCreateParam(seg.substring(1), null, false);
            } else {
                current = current.getOrCreateLiteral(seg);
            }
        }
        if (current.handler != null) {
            throw new IllegalStateException(
                "Duplicate route detected: " + key + " " + path
            );
        }
        current.handler = handler;
    }

    public RouteMatch match(String method, String path) {
        String key = method == null ? "" : method.toUpperCase(Locale.ROOT);
        // Split the raw path before decoding. An encoded slash belongs to its
        // original segment and must not create a new route segment.
        String rawPath = path;
        // Fast path: exact match cache bypasses trie for routes without variables
        if (rawPath.indexOf('%') < 0 && rawPath.indexOf('+') < 0) {
            String cacheKey = key.concat(":").concat(rawPath);
            RouteHandler exact = exactCache.get(cacheKey);
            if (exact != null) return new RouteMatch(exact, Map.of());
            if ("HEAD".equals(key)) {
                exact = exactCache.get("GET:".concat(rawPath));
                if (exact != null) return new RouteMatch(exact, Map.of());
            }
        }
        TrieNode root = methodRoots.get(key);
        RouteMatch result = matchTrie(root, rawPath);
        if (result != null || !"HEAD".equals(key)) return result;
        return matchTrie(methodRoots.get("GET"), rawPath);
    }

    /** Matches raw path segments after decoding each segment independently. */
    private RouteMatch matchTrie(TrieNode root, String path) {
        if (root == null) {
            return null;
        }
        String[] segments = PathPattern.splitPath(path);
        return matchFrom(root, segments, 0, new LinkedHashMap<>());
    }

    private RouteMatch matchFrom(TrieNode node, String[] rawSegments, int index,
                                 Map<String, String> vars) {
        if (index == rawSegments.length) {
            return node.handler == null ? null : new RouteMatch(node.handler, Map.copyOf(vars));
        }
        String seg = PathPattern.decodeSegment(rawSegments[index]);
        if (seg == null || seg.isEmpty()
                || seg.length() > PathPattern.MAX_SEGMENT_LENGTH
                || PathPattern.isPathTraversalSegment(seg)
                || PathPattern.containsPathTraversal(seg)) return null;

        TrieNode literal = node.literals == null ? null : node.literals.get(seg);
        if (literal != null) {
            RouteMatch result = matchFrom(literal, rawSegments, index + 1, vars);
            if (result != null) return result;
        }
        if (node.paramChildren == null) return null;
        var candidates = new ArrayList<>(node.paramChildren);
        candidates.sort(Comparator.comparingInt(RouteIndex::parameterSpecificity).reversed());
        for (TrieNode param : candidates) {
            Map<String, String> next = new LinkedHashMap<>(vars);
            if (param.wildcard) {
                StringBuilder remainder = new StringBuilder();
                for (int i = index; i < rawSegments.length; i++) {
                    if (rawSegments[i].isEmpty()) {
                        remainder.setLength(0);
                        break;
                    }
                    String part = PathPattern.decodeSegment(rawSegments[i]);
                    if (part == null || part.isEmpty()) {
                        remainder.setLength(0);
                        break;
                    }
                    if (remainder.length() > 0) remainder.append('/');
                    remainder.append(part);
                }
                String decoded = remainder.toString();
                if (decoded == null || decoded.isEmpty() || PathPattern.containsPathTraversal(decoded)) continue;
                next.put(param.paramName, decoded);
                if (param.handler != null) return new RouteMatch(param.handler, Map.copyOf(next));
                continue;
            }
            if (param.paramPattern != null && !param.paramPattern.matcher(seg).matches()) continue;
            next.put(param.paramName, seg);
            RouteMatch result = matchFrom(param, rawSegments, index + 1, next);
            if (result != null) return result;
        }
        return null;
    }

    private static int parameterSpecificity(TrieNode node) {
        if (node.wildcard) return 0;
        return node.paramPattern == null ? 10 : 20;
    }

    // ---- Trie node ----

    private static final class TrieNode {

        String segment; // literal segment
        String paramName; // non-null for param nodes
        Pattern paramPattern; // regex constraint for param (null = any)
        boolean wildcard; // {path:.*} wildcard
        Map<String, TrieNode> literals; // literal children
        List<TrieNode> paramChildren;
        RouteHandler handler; // non-null if route terminates here
        boolean frozen;

        TrieNode() {}

        TrieNode getOrCreateLiteral(String seg) {
            if (frozen) {
                throw new IllegalStateException("RouteIndex is frozen");
            }
            if (paramChildren != null && paramChildren.stream().anyMatch(n -> n.wildcard)) {
                throw new IllegalArgumentException(
                    "Cannot register literal segment '" + seg
                        + "' under a wildcard — wildcard captures all remaining path segments");
            }
            if (literals == null) {
                literals = new LinkedHashMap<>();
            }
            return literals.computeIfAbsent(seg, k -> {
                TrieNode n = new TrieNode();
                n.segment = k;
                return n;
            });
        }

        TrieNode getOrCreateParam(
            String name,
            Pattern regex,
            boolean isWildcard
        ) {
            if (frozen) {
                throw new IllegalStateException("RouteIndex is frozen");
            }
            if (paramChildren == null) paramChildren = new ArrayList<>();
            for (TrieNode existing : paramChildren) {
                boolean sameRegex = (existing.paramPattern == null && regex == null)
                    || (existing.paramPattern != null && regex != null
                        && existing.paramPattern.pattern().equals(regex.pattern()));
                if (existing.paramName.equals(name) && sameRegex && existing.wildcard == isWildcard) {
                    return existing;
                }
            }
            if (isWildcard && literals != null && !literals.isEmpty()) {
                throw new IllegalArgumentException(
                    "Cannot register wildcard under a node that already has literal children");
            }
            TrieNode paramChild = new TrieNode();
            paramChild.paramName = name;
            paramChild.paramPattern = regex;
            paramChild.wildcard = isWildcard;
            paramChildren.add(paramChild);
            return paramChild;
        }

        void freeze() {
            frozen = true;
            if (literals != null) {
                for (TrieNode child : literals.values()) {
                    child.freeze();
                }
                literals = Map.copyOf(literals);
            }
            if (paramChildren != null) {
                for (TrieNode child : paramChildren) child.freeze();
            }
        }

        @Override
        public String toString() {
            if (segment != null) return segment;
            if (paramName != null) return "{" + paramName + (wildcard ? ":*}" : "}");
            return "/";
        }
    }

    public record RouteMatch(
        RouteHandler handler,
        Map<String, String> pathVariables
    ) {}
}
