package com.jujin.freeway.ioc.internal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ScopeControl {
    private static final ScopedValue<ScopeSession> SCOPED_SESSION = ScopedValue.newInstance();

    private final BooleanSupplier closedCheck;
    private final Set<ScopeSession> openScopes = ConcurrentHashMap.newKeySet();

    ScopeControl(BooleanSupplier closedCheck) {
        this.closedCheck = closedCheck;
    }

    <T> T within(Supplier<T> work) {
        if (closedCheck.getAsBoolean()) {
            throw new IllegalStateException("Container is closed");
        }
        ScopeSession session = new ScopeSession();
        openScopes.add(session);
        try {
            return ScopedValue.where(SCOPED_SESSION, session).call(work::get);
        } finally {
            closeSession(session);
        }
    }

    // ---- Shutdown ----

    RuntimeException closeOpenScopes(RuntimeException failure) {
        for (ScopeSession session : List.copyOf(openScopes)) {
            try {
                closeSession(session);
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = new RuntimeException("Unable to close open scope", ex);
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        return failure;
    }

    // ---- Current scope lookup ----

    ScopeSession current() {
        if (!SCOPED_SESSION.isBound()) {
            return null;
        }
        ScopeSession session = SCOPED_SESSION.get();
        return session.isClosed() ? null : session;
    }

    // ---- Internal ----

    private void closeSession(ScopeSession session) {
        if (openScopes.remove(session)) {
            session.close();
        }
    }
}
