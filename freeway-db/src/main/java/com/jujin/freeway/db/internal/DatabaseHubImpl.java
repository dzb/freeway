package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseHub;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseHubImpl implements DatabaseHub {

    private static final Logger LOG = LoggerFactory.getLogger(
        DatabaseHubImpl.class
    );
    private final Map<String, Database> databases;

    public DatabaseHubImpl(Map<String, Database> databases) {
        this.databases = Map.copyOf(databases);
        for (var entry : databases.entrySet()) {
            LOG.debug("Registered database '{}'", entry.getKey());
        }
    }

    @Override
    public Database get(String name) {
        return databases.get(name);
    }

    @Override
    public Database primary() {
        Database primary = databases.get("primary");
        if (primary == null) {
            throw new IllegalStateException("No primary database configured");
        }
        return primary;
    }

    @Override
    public Map<String, Database> all() {
        return databases;
    }
}
