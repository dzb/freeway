package com.jujin.freeway2.boot;

import java.util.List;
import java.util.Map;

public interface AppConfig {
    String get(String key);

    List<String> profiles();

    Map<String, String> asMap();
}
