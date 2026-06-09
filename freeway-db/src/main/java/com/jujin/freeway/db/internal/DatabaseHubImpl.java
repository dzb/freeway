package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseHub;
import com.jujin.freeway.db.DatabaseNamed;
import com.jujin.freeway.ioc.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DatabaseHubImpl implements DatabaseHub {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseHubImpl.class);
    private final Map<String, Database> databases;

    public DatabaseHubImpl(Extension<DatabaseNamed> registrations) {
        Map<String, Database> map = new LinkedHashMap<>();
        for (DatabaseNamed entry : registrations.all()) {
            map.put(entry.name(), entry.db());
            LOG.debug("Registered database '{}'", entry.name());
        }
        this.databases = Map.copyOf(map);
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
