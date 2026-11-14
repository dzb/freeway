package com.jujin.freeway.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property baseline for the SQL builder's parameter scanner: placeholders
 * outside literals/comments bind exactly one value each, a repeated named
 * parameter reuses its first value, and placeholder-looking text inside a
 * string, identifier or comment binds nothing.
 */
class SqlParameterPropertyTest {

    @Test
    void randomFragmentsBindExactlyTheValuesTheyName() {
        Random random = new Random(0x5A1);
        for (int iteration = 0; iteration < 250; iteration++) {
            Sql query = Sql.select("*").from("t");
            List<Object> expected = new ArrayList<>();
            int clauses = 1 + random.nextInt(4);
            for (int clause = 0; clause < clauses; clause++) {
                String column = "c" + clause;
                int kind = random.nextInt(5);
                switch (kind) {
                    case 0 -> {
                        Object value = value(random);
                        query = query.where(column + " = ?", value);
                        expected.add(value);
                    }
                    case 1 -> {
                        Object value = value(random);
                        query = query.where(column + " = :p" + clause, value);
                        expected.add(value);
                    }
                    case 2 -> {
                        Object value = value(random);
                        query = query.where("(" + column + " = :same OR d = :same)", value);
                        expected.add(value);
                        expected.add(value);
                    }
                    case 3 -> {
                        Object value = value(random);
                        query = query.where(column + " = ? /* ? :ghost */", value);
                        expected.add(value);
                    }
                    default -> {
                        Object value = value(random);
                        query = query.where(column + "::text = ? AND note = 'a? :ghost'", value);
                        expected.add(value);
                    }
                }
            }

            assertArrayEquals(expected.toArray(), query.args(),
                "iteration " + iteration + " sql=" + query.toSql());
            String sql = query.toSql();
            assertFalse(sql.contains(":p"), "named placeholders must be rewritten: " + sql);
            assertFalse(sql.contains(":same"), "named placeholders must be rewritten: " + sql);
            // `:ghost` inside a comment/string is preserved verbatim; only
            // real named parameters are rewritten.
        }
    }

    private static Object value(Random random) {
        return random.nextBoolean()
            ? random.nextInt(1_000)
            : "v" + random.nextInt(1_000) + "_" + random.nextInt(9);
    }
}
