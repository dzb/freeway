package com.jujin.freeway.cloud.context;

import java.lang.ScopedValue;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Cross-boundary propagation carrier: the single {@link ScopedValue} slot that
 * crosses process boundaries via {@link Propagator}s.
 *
 * <p>Carries exactly three typed sub-contexts — {@link TraceContext}
 * (infrastructure-owned), {@link PrincipalContext} (security-owned, never
 * forgeable by arbitrary code) and {@link Baggage} (application-owned). It does
 * NOT carry business data, config snapshots or object caches.
 */
public final class InvocationContext {

    private static final ScopedValue<InvocationContext> CURRENT = ScopedValue.newInstance();

    /**
     * Ambient fallback for threads running outside any structured scope
     * (background jobs, startup tasks, tests). Same-thread only — unlike the
     * ScopedValue tier it never propagates to child threads. Written by
     * context-aware helpers (e.g. {@code TracerDefault} spans) via
     * {@link #replaceAmbient}; {@link #current()} reads it when no scoped
     * binding exists.
     */
    private static final ThreadLocal<InvocationContext> AMBIENT = new ThreadLocal<>();

    private final TraceContext trace;
    private final PrincipalContext principal;
    private final Baggage baggage;

    private InvocationContext(TraceContext trace, PrincipalContext principal, Baggage baggage) {
        this.trace = trace;
        this.principal = principal;
        this.baggage = baggage;
    }

    /** Builds a context; any sub-context may be {@code null} (meaning "not set"). */
    public static InvocationContext of(TraceContext trace, PrincipalContext principal, Baggage baggage) {
        return new InvocationContext(trace, principal, baggage);
    }

    /**
     * The context bound to the current thread: the structured
     * {@link ScopedValue} binding when one exists (propagates to virtual-thread
     * children), otherwise this thread's ambient fallback.
     */
    public static Optional<InvocationContext> current() {
        try {
            return Optional.of(CURRENT.get());
        } catch (NoSuchElementException unscoped) {
            return Optional.ofNullable(AMBIENT.get());
        }
    }

    /**
     * Replaces this thread's ambient fallback and returns the previous one
     * ({@code null} when none was set). Callers must restore the returned
     * value when their scope ends — save/restore stays correct even for
     * out-of-order closes. Pass {@code null} to clear.
     */
    public static InvocationContext replaceAmbient(InvocationContext ctx) {
        InvocationContext previous = AMBIENT.get();
        if (ctx == null) {
            AMBIENT.remove();
        } else {
            AMBIENT.set(ctx);
        }
        return previous;
    }

    /** Runs {@code work} with this context bound for the current thread (and its virtual-thread children). */
    public static <T, X extends Throwable> T runWith(InvocationContext ctx, ScopedValue.CallableOp<? extends T, X> work) throws X {
        return ScopedValue.where(CURRENT, ctx).call(work);
    }

    /** Runs {@code work} with this context bound for the current thread. */
    public static void runWith(InvocationContext ctx, Runnable work) {
        ScopedValue.where(CURRENT, ctx).run(work);
    }

    public TraceContext trace() {
        return trace;
    }

    public PrincipalContext principal() {
        return principal;
    }

    public Baggage baggage() {
        return baggage;
    }
}
