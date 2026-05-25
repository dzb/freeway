package com.jujin.freeway.ioc.internal;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

final class DefaultSymbolSource implements SymbolSource {
    private static final int MAX_EXPAND_DEPTH = 40;
    private final CopyOnWriteArrayList<SymbolProvider> providers = new CopyOnWriteArrayList<>();

    DefaultSymbolSource(List<SymbolProvider> providers) {
        this.providers.addAll(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * 创建标准符号源，按优先级依次从 System Property 和 System Env 查找。
     * <p>
     * 查找顺序：先 {@link System#getProperty(String)}，未命中再
     * {@link System#getenv(String)}，即 Property 优先于 Env。
     * <p>
     * 注意 {@code System.getenv()} 的大小写行为依赖操作系统：
     * <ul>
     *   <li><b>Windows</b>：不区分大小写，{@code PATH} 与 {@code path} 等效；</li>
     *   <li><b>Linux / macOS</b>：区分大小写，{@code PATH} 与 {@code path} 被视为不同变量。</li>
     * </ul>
     */
    static DefaultSymbolSource standard() {
        List<SymbolProvider> providers = new ArrayList<>();
        providers.add(name -> System.getProperty(name));
        providers.add(name -> System.getenv(name));
        return new DefaultSymbolSource(providers);
    }

    void register(SymbolProvider provider) {
        providers.add(Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public String resolve(String name) {
        String value = raw(name);
        if (value != null) {
            return expand(value);
        }
        throw new IllegalArgumentException("Unknown symbol: " + name);
    }

    private String raw(String name) {
        for (SymbolProvider provider : providers) {
            String value = provider.lookup(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 递归展开字符串中的 {@code ${...}} 符号引用。
     * <p>
     * 展开后的值如果还包含 {@code ${...}} 表达式，会继续递归展开。
     * 注意：这意味着如果某个符号的值本身包含未转义的 {@code ${...}} 语法，
     * 且恰好匹配另一个符号名，也会被展开。
     */
    @Override
    public String expand(String input) {
        return expand(input, 0);
    }

    /**
     * 递归展开字符串中的 {@code ${...}} 符号引用，带深度限制防止栈溢出。
     * <p>
     * 展开后的值如果还包含 {@code ${...}} 表达式，会继续递归展开。
     * 注意：这意味着如果某个符号的值本身包含未转义的 {@code ${...}} 语法，
     * 且恰好匹配另一个符号名，也会被展开。
     * <p>
     * 默认值语法 {@code ${name:-default}} 中 default 值如果包含 {@code ${...}}
     * 也会被递归展开，因此需避免在默认值中引入循环引用。
     *
     * @param input 待展开的字符串
     * @param depth 当前递归深度
     * @return 展开后的字符串
     * @throws IllegalArgumentException 如果深度超过限制或符号未闭合
     */
    private String expand(String input, int depth) {
        if (depth > MAX_EXPAND_DEPTH) {
            throw new IllegalArgumentException(
                "Symbol expansion exceeded max depth of " + MAX_EXPAND_DEPTH + ": " + input
            );
        }
        if (input == null || input.indexOf("${") < 0) {
            return input;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            int start = input.indexOf("${", i);
            if (start < 0) {
                out.append(input, i, input.length());
                break;
            }
            out.append(input, i, start);
            int end = input.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed symbol expression in: " + input);
            }
            String expr = input.substring(start + 2, end);
            String symbol = expr;
            String defaultValue = null;
            int colon = expr.indexOf(':');
            if (colon >= 0) {
                symbol = expr.substring(0, colon);
                defaultValue = expr.substring(colon + 1);
            }
            String value = raw(symbol);
            if (value == null) {
                value = defaultValue;
            }
            if (value == null) {
                throw new IllegalArgumentException("Unknown symbol: " + symbol);
            }
            out.append(expand(value, depth + 1));
            i = end + 1;
        }
        return out.toString();
    }
}
