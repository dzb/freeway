package com.jujin.freeway.http.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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
 * independent of the total route count. */
public final class RouteIndex {

    private static final int MAX_REGEX_LENGTH = PathPattern.MAX_REGEX_LENGTH;

    private final Map<String, TrieNode> methodRoots = new ConcurrentHashMap<>();

    public RouteIndex(List<Route> routes, List<RouteGroup> groups) {
        // Phase 1: collect all routes
        List<Route> all = new ArrayList<>();
        for (Route route : routes == null ? List.<Route>of() : routes) {
            all.add(route);
        }
        for (RouteGroup group : groups == null
            ? List.<RouteGroup>of()
            : groups) {
            for (Route route : group.expand()) {
                all.add(route);
            }
        }
        // Phase 2: insert into trie — duplicate detection done at insert time
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
        if (node.paramChild != null) {
            n += countLeaves(node.paramChild);
        }
        return n;
    }

    private void addRoute(String method, String path, RouteHandler handler) {
        String key = method == null ? "" : method.toUpperCase();
        TrieNode root = methodRoots.computeIfAbsent(key, k -> new TrieNode());
        String[] segments = PathPattern.splitPath(path);
        TrieNode current = root;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
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
        String key = method == null ? "" : method.toUpperCase();
        TrieNode root = methodRoots.get(key);
        RouteMatch result = matchTrie(root, path);
        if (result != null || !"HEAD".equals(key)) {
            return result;
        }
        // HEAD fallback to GET
        return matchTrie(methodRoots.get("GET"), path);
    }

    private RouteMatch matchTrie(TrieNode root, String path) {
        if (root == null) {
            return null;
        }
        String[] segments = PathPattern.splitPath(path);
        Map<String, String> vars = new LinkedHashMap<>();
        TrieNode current = root;
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty() || PathPattern.isPathTraversalSegment(seg)) {
                return null;
            }
            // Try literal match first
            TrieNode literal =
                current.literals != null ? current.literals.get(seg) : null;
            if (literal != null) {
                current = literal;
                continue;
            }
            // Try param match
            TrieNode param = current.paramChild;
            if (param != null) {
                // Wildcard consumes remaining segments
                if (param.wildcard) {
                    for (int j = i; j < segments.length; j++) {
                        if (segments[j].isEmpty()) {
                            return null;
                        }
                    }
                    String remainder = String.join(
                        "/",
                        Arrays.copyOfRange(segments, i, segments.length)
                    );
                    if (
                        remainder.isEmpty() ||
                        PathPattern.containsPathTraversal(remainder)
                    ) {
                        return null;
                    }
                    vars.put(param.paramName, remainder);
                    current = param;
                    break;
                }
                // Regex constraint check
                if (
                    param.paramPattern != null &&
                    !param.paramPattern.matcher(seg).matches()
                ) {
                    return null;
                }
                vars.put(param.paramName, seg);
                current = param;
                continue;
            }
            return null;
        }
        if (current.handler == null) {
            return null;
        }
        return new RouteMatch(current.handler, vars);
    }

    // ---- Trie node ----

    private static final class TrieNode {

        String segment; // literal segment
        String paramName; // non-null for param nodes
        Pattern paramPattern; // regex constraint for param (null = any)
        boolean wildcard; // {path:.*} wildcard
        Map<String, TrieNode> literals; // literal children
        TrieNode paramChild; // param child (at most one per node)
        RouteHandler handler; // non-null if route terminates here
        boolean frozen;

        TrieNode() {}

        TrieNode getOrCreateLiteral(String seg) {
            if (frozen) {
                throw new IllegalStateException("RouteIndex is frozen");
            }
            if (paramChild != null && paramChild.wildcard) {
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
            if (paramChild == null) {
                if (isWildcard && literals != null && !literals.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Cannot register wildcard '{"
                            + name + ":.*}' under a node that already has literal children");
                }
                paramChild = new TrieNode();
                paramChild.paramName = name;
                paramChild.paramPattern = regex;
                paramChild.wildcard = isWildcard;
            } else {
                if (!paramChild.paramName.equals(name)
                        || !Objects.equals(paramChild.paramPattern, regex)
                        || paramChild.wildcard != isWildcard) {
                    throw new IllegalArgumentException(
                        "Conflicting parameter definitions at same path level: "
                            + "'{" + paramChild.paramName + (paramChild.paramPattern != null ? ":" + paramChild.paramPattern.pattern() : "") + "}'"
                            + " vs "
                            + "'{" + name + (regex != null ? ":" + regex.pattern() : "") + "}'");
                }
            }
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
            if (paramChild != null) {
                paramChild.freeze();
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
