package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseHub;
import com.jujin.freeway.db.DatabaseNamed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseHubImpl implements DatabaseHub {

    private static final Logger LOG = LoggerFactory.getLogger(
        DatabaseHubImpl.class
    );
    private final Map<String, Database> databases;

    /**
     * IoC constructor — {@code List<DatabaseNamed>} populated from module
     * contributions via {@code binder.contribute(DatabaseNamed.class).add(...)}.
     */
    public DatabaseHubImpl(List<DatabaseNamed> entries) {
        this(toMap(entries));
    }

    public DatabaseHubImpl(Map<String, Database> databases) {
        this.databases = Map.copyOf(databases);
        for (var entry : databases.entrySet()) {
            LOG.debug("Registered database '{}'", entry.getKey());
        }
    }

    private static Map<String, Database> toMap(List<DatabaseNamed> entries) {
        Map<String, Database> map = new LinkedHashMap<>();
        for (DatabaseNamed entry : entries) {
            map.put(entry.name(), entry.db());
        }
        return map;
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
