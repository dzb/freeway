package com.jujin.freeway2.db.internal;

import com.jujin.freeway2.db.Database;
import com.jujin.freeway2.db.DatabaseHub;
import com.jujin.freeway2.ioc.annotation.ExtensionPoint;
import java.util.Map;

public final class DatabaseHubImpl implements DatabaseHub {
    private final Map<String, Database> databases;

    public DatabaseHubImpl(@ExtensionPoint(Database.class) Map<String, Database> databases) {
        this.databases = Map.copyOf(databases);
    }

    @Override
    public Database get(String name) {
        return databases.get(name);
    }

    @Override
    public Map<String, Database> all() {
        return databases;
    }
}
