package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.ScopeHandle;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class ScopeControl {
    private final BooleanSupplier closedCheck;
    private final ThreadLocal<Deque<ScopeSession>> scopeStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final Set<ScopeSession> openScopes = ConcurrentHashMap.newKeySet();

    ScopeControl(BooleanSupplier closedCheck) {
        this.closedCheck = closedCheck;
    }

    ScopeHandle open() {
        if (closedCheck.getAsBoolean()) {
            throw new IllegalStateException("Container is closed");
        }
        ScopeSession session = new ScopeSession();
        scopeStack.get().push(session);
        openScopes.add(session);
        return () -> close(session);
    }

    RuntimeException closeOpenScopes(RuntimeException failure) {
        for (ScopeSession session : List.copyOf(openScopes)) {
            try {
                close(session);
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

    ScopeSession current() {
        Deque<ScopeSession> stack = scopeStack.get();
        while (!stack.isEmpty()) {
            ScopeSession session = stack.peek();
            if (!session.isClosed()) {
                return session;
            }
            stack.pop();
        }
        return null;
    }

    private void close(ScopeSession session) {
        if (session == null) {
            return;
        }
        Deque<ScopeSession> stack = scopeStack.get();
        if (stack.peek() == session) {
            stack.pop();
        } else {
            stack.remove(session);
        }
        if (openScopes.remove(session)) {
            session.close();
        }
    }
}
