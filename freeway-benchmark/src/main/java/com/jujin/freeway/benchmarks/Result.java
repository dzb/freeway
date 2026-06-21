package com.jujin.freeway.benchmarks;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public record Result(String engine, String mode, int requests, int ok, int errors,
                      double rps, long p50us, long p95us, long p99us) {
    @Override public String toString() {
        return String.format(Locale.ROOT,
            "engine=%s mode=%s requests=%d ok=%d errors=%d rps=%.0f p50=%d p95=%d p99=%d",
            engine, mode, requests, ok, errors, rps, p50us, p95us, p99us);
    }
    public static Result median(List<Result> rs) {
        var sorted = rs.stream().sorted(Comparator.comparingDouble(Result::rps)).toList();
        Result mid = sorted.get(sorted.size() / 2);
        return new Result(mid.engine, mid.mode,
            mInt(rs, Result::requests), mInt(rs, Result::ok), mInt(rs, Result::errors),
            mDbl(rs, Result::rps), mLong(rs, Result::p50us),
            mLong(rs, Result::p95us), mLong(rs, Result::p99us));
    }
    public static long percentile(long[] s, double f) {
        if (s.length == 0) return 0;
        return s[Math.clamp((int)Math.ceil(s.length * f) - 1, 0, s.length - 1)];
    }
    private static int mInt(List<Result> rs, ToIntFunction<Result> g) { return rs.stream().mapToInt(g).sorted().skip(rs.size()/2).findFirst().orElse(0); }
    private static long mLong(List<Result> rs, ToLongFunction<Result> g) { return rs.stream().mapToLong(g).sorted().skip(rs.size()/2).findFirst().orElse(0); }
    private static double mDbl(List<Result> rs, ToDoubleFunction<Result> g) { return rs.stream().mapToDouble(g).sorted().skip(rs.size()/2).findFirst().orElse(0); }
    public static Result fromLine(String line) {
        String[] parts = line.substring("RESULT ".length()).split(" ");
        return new Result(val(parts, "engine"), val(parts, "mode"), Integer.parseInt(val(parts, "requests")), Integer.parseInt(val(parts, "ok")), Integer.parseInt(val(parts, "errors")), Double.parseDouble(val(parts, "rps")), Long.parseLong(val(parts, "p50")), Long.parseLong(val(parts, "p95")), Long.parseLong(val(parts, "p99")));
    }
    private static String val(String[] parts, String key) { for (String p : parts) if (p.startsWith(key + "=")) return p.substring(key.length() + 1); throw new IllegalArgumentException("Missing " + key); }
}
