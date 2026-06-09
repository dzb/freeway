package com.jujin.freeway.db;

import java.util.Objects;

public record DatabaseNamed(String name, Database db) {
    public DatabaseNamed {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(db, "db");
    }
}
