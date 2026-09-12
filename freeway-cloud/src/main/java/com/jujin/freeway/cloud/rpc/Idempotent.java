package com.jujin.freeway.cloud.rpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a remote operation as safe to replay on an ambiguous
 * transport outcome — timeout, mid-flight I/O failure or a 5xx answer, where
 * the peer may already have applied the request.
 *
 * <p>The resilience loop retries ambiguous outcomes only for idempotent
 * operations. Remote calls travel as {@code POST} (non-idempotent by
 * default), so a consumer marks the interface method — or the whole
 * interface — with {@code @Idempotent} when its remote handler replays
 * safely (queries, upserts, handlers that deduplicate). Read reflectively
 * at dispatch time by {@link RemoteProxyFactory}; no classpath scanning.
 *
 * <pre>{@code
 * public interface UserApi {
 *     @Idempotent Greeting greet(String name);   // safe: pure query
 *     Account open(String name);                 // unsafe: must not replay
 * }
 * }</pre>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
