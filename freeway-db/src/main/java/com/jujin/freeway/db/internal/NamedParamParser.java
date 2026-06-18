package com.jujin.freeway.db.internal;

import java.util.List;

final class NamedParamParser {

    private NamedParamParser() {}

    record Result(
        List<String> names,
        String sql,
        List<Integer> parameterIndexes
    ) {}

    static Result parse(String sql) {
        return SqlTextScanner.parseNamed(sql);
    }

    static List<Integer> positionalPlaceholderIndexes(String sql) {
        return SqlTextScanner.positionalPlaceholderIndexes(sql);
    }

    static boolean hasNamedPlaceholders(String sql) {
        return SqlTextScanner.hasNamedPlaceholders(sql);
    }
}
