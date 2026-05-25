package com.jujin.freeway.db;

import java.util.Map;

public interface DatabaseHub {
    Database get(String name);

    Map<String, Database> all();
}
