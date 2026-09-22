/**
 * HPACK header (de)compression for HTTP/2 — a leaf of the
 * {@code engine.http2} codec (static table, Huffman coding, encode/decode
 * context), <strong>no stability promise</strong>. It is reached only
 * through {@code engine.http2}'s connection state; nothing outside the
 * codec may depend on its shape.
 */
package com.jujin.freeway.http.engine.http2.hpack;
