/**
 * Static resource serving — application-facing: {@code StaticResourceMount}
 * declares one mount (root, prefix, fallthrough) for the pipeline. Resolved
 * paths stay inside the mount root with symlink traversal refused, and a
 * miss — inside the mount or on the fallthrough — answers through the
 * shared {@code ErrorResponses} exactly like a route miss.
 */
package com.jujin.freeway.http.staticfile;
