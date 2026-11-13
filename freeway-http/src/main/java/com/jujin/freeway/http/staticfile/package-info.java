/**
 * Static resource serving — application-facing: {@code StaticResourceMount}
 * declares one mount (root, prefix, fallthrough, optional history-mode SPA
 * fallback) for the pipeline. Resolved paths stay inside the mount root with
 * symlink traversal refused. A miss inside the mount answers through the route
 * chain when fallthrough is on; an opt-in SPA fallback is the terminal
 * {@code index.html} hop after route dispatch has also missed. Everything else
 * answers through the shared {@code ErrorResponses} exactly like a route miss.
 */
package com.jujin.freeway.http.staticfile;
