# 修复清单 - 修正版

## 📋 经过调用上下文验证后的真实问题清单

### ❌ 误判项（已排除）

#### 1. ServiceRuntime 线程安全问题

**原评估**：双重检查锁定不完整，`targetCache` 非 volatile，可能看到部分初始化对象

**验证结果**：❌ **误判**

**实际代码分析**：
```java
// ServiceRuntime.java#L68-L83
Object cached = targetCache.get(key);  // ConcurrentHashMap.get() 线程安全
if (cached != null) {
    return binding.type().cast(cached);
}
// ...
synchronized (targetCache) {
    cached = targetCache.get(key);
    if (cached == null) {
        cached = binding.directInstance();  // ← 关键：返回完全构造的对象
        targetCache.put(key, cached);
    }
}
```

**为什么正确**：
1. ✓ `targetCache` 是 `ConcurrentHashMap`，`get()` 本身就是线程安全的
2. ✓ `binding.directInstance()` 返回**完全构造的对象**（不是部分初始化）
3. ✓ `synchronized` 块保证了写入的原子性和可见性
4. ✓ 这不是经典的双重检查锁定（DCL）问题，因为对象不是 `volatile` 字段

**结论**：代码设计正确，无需修复

---

#### 2. RouteIndex 正则表达式未缓存

**原评估**：每次请求都编译正则表达式，性能差

**验证结果**：❌ **误判**

**实际代码分析**：
```java
// RouteIndex.java#L86-L96（在 addRoute 方法中，启动时执行）
if (regexStr.length() > MAX_REGEX_LENGTH) {
    throw new IllegalArgumentException(...);
}
if (!".*".equals(regexStr)) {
    regex = Pattern.compile(regexStr);  // ← 只在启动时编译一次
}
```

**为什么正确**：
1. ✓ `addRoute()` 只在**构造函数**中调用（启动时）
2. ✓ 编译后的 `Pattern` 存储在 `TrieNode.paramPattern` 中
3. ✓ 请求匹配时直接使用缓存的 `Pattern`（第 156 行）

**结论**：设计正确，性能优化已实现

---

### ✅ 真实存在的问题（需要修复）

#### 1. ConnectionPool 关闭逻辑使用忙等待 ⚠️

**问题描述**：
```java
// ConnectionPool.java#L142-L150
long deadline = System.nanoTime() + config.connectionTimeout().toNanos();
while (total.get() > 0 && System.nanoTime() < deadline) {
    try {
        Thread.sleep(10);  // ← 忙等待，CPU 效率低
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
    }
}
```

**影响**：
- 关闭时 CPU 使用率升高
- 不够优雅，但功能正确

**修复建议**：
```java
// 使用 CountDownLatch 或 Phaser
CountDownLatch latch = new CountDownLatch(total.get());
// 在连接归还时调用 latch.countDown()
latch.await(config.connectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
```

**优先级**：低（功能正确，只是性能可优化）

---

#### 2. WebServer 启动检测不可靠 ⚠️

**问题描述**：
```java
// WebServer.java#L152-L170
private static void awaitReady(String host, int port) {
    // ...
    try (Socket s = new Socket(host, port)) {
        s.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(...));
        s.setSoTimeout(1000);
        if (s.getInputStream().read() != -1) {  // ← 仅检查是否可读
            return;
        }
    }
}
```

**问题**：
1. 仅检查 `read() != -1`，未验证是否是 HTTP 响应
2. 如果服务器返回错误（如 400），也会误判为就绪

**修复建议**：
```java
// 验证 HTTP 响应状态
String response = new String(s.getInputStream().readAllBytes(), UTF_8);
if (response.startsWith("HTTP/")) {
    return; // 确认是 HTTP 响应
}
```

**优先级**：中（影响启动可靠性）

---

#### 3. WebSocket CORS 安全漏洞 ⚠️

**问题描述**：
```java
// WebServer.java#L79-L86
public WebSocketMatch websocket(String method, String path, String origin) {
    String resolvedOrigin = corsFilter.resolveAllowedOrigin(origin);
    if (resolvedOrigin == null && origin != null && !origin.isBlank()) {
        LOG.warn("WebSocket upgrade rejected...");
        return null;
    }
    return websocketIndex.match(method, path);
}
```

**问题**：
- `origin == null || origin.isBlank()` 时，`resolvedOrigin` 也为 `null`
- 但代码没有拒绝空 origin，存在安全风险

**修复建议**：
```java
if (resolvedOrigin == null) {
    if (origin == null || origin.isBlank()) {
        LOG.warn("WebSocket upgrade rejected: missing origin");
    } else {
        LOG.warn("WebSocket upgrade rejected: origin not allowed: {}", origin);
    }
    return null;
}
```

**优先级**：高（安全漏洞）

---

#### 4. JsonParser 缺少安全限制 ⚠️

**问题描述**：
- 无嵌套深度限制 → 栈溢出风险
- 无字符串长度限制 → OOM 风险
- 无数组/对象大小限制 → OOM 风险

**修复建议**：
```java
private static final int MAX_DEPTH = 1000;
private static final int MAX_STRING_LENGTH = 10 * 1024 * 1024; // 10MB
private static final int MAX_ARRAY_SIZE = 1_000_000;

private Object parseValue(int depth) {
    if (depth > MAX_DEPTH) {
        throw new JsonException("JSON nesting too deep");
    }
    // ...
}
```

**优先级**：高（安全漏洞）

---

#### 5. BindingIndex.findUnique() 性能问题 ⚠️

**问题描述**：
```java
// BindingIndex.java#L80-L126
<T> BindingImpl<T> findUnique(Class<T> type) {
    // 线性扫描所有绑定，O(n) 复杂度
    List<BindingImpl<T>> matches = new ArrayList<>();
    for (BindingImpl<?> binding : bindings.values()) {
        if (binding.type().equals(type)) {
            matches.add((BindingImpl<T>) binding);
        }
    }
    // ...
}
```

**影响**：
- 每次 `container.get(type)` 都执行线性搜索
- 大量绑定时性能差

**修复建议**：
```java
// 添加类型索引
private final Map<Class<?>, List<BindingImpl<?>>> typeIndex = new ConcurrentHashMap<>();
```

**优先级**：中（性能优化）

---

## 📊 修复优先级和工作量评估

### 立即修复（高优先级）

| # | 问题 | 文件 | 复杂度 | 预计工时 |
|---|------|------|--------|---------|
| 1 | WebSocket CORS 安全漏洞 | WebServer.java | 低 | 0.5 天 |
| 2 | JsonParser 安全限制 | JsonParser.java | 中 | 2 天 |

**小计**：2.5 天

---

### 短期修复（中优先级）

| # | 问题 | 文件 | 复杂度 | 预计工时 |
|---|------|------|--------|---------|
| 3 | WebServer 启动检测优化 | WebServer.java | 低 | 1 天 |
| 4 | BindingIndex 性能优化 | BindingIndex.java | 中 | 2 天 |
| 5 | InjectionResolver 作用域验证 | InjectionResolver.java | 中 | 2 天 |

**小计**：5 天

---

### 可选优化（低优先级）

| # | 问题 | 文件 | 复杂度 | 预计工时 |
|---|------|------|--------|---------|
| 6 | ConnectionPool 关闭逻辑优化 | ConnectionPool.java | 低 | 1 天 |
| 7 | ThreadLocal 清理 | 多处 | 中 | 2 天 |
| 8 | 异常分类 | 多处 | 高 | 3 天 |

**小计**：6 天（可选）

---

## 🎯 修正后的总工作量

- **立即修复**：2.5 天
- **短期修复**：5 天
- **可选优化**：6 天（按需）
- **测试补充**：15 天（覆盖率提升至 60%+）

**总计**：**22.5 天（约 4.5 周）**，比之前的 8 周减少 3.5 周

---

## ✅ 验证通过的问题（无需修复）

### 1. ServiceRuntime 线程安全 ✓

**验证结论**：代码设计正确，`ConcurrentHashMap` + `synchronized` 组合已保证线程安全

### 2. RouteIndex 正则表达式缓存 ✓

**验证结论**：已在启动时编译并缓存，性能优化已实现

### 3. ConnectionPool 忙等待 ✓

**验证结论**：虽然使用 `Thread.sleep(10)`，但功能正确，只是性能可优化（低优先级）

---

## 🚀 下一步行动

### 立即开始（第 1 周）

1. ✅ 修复 WebSocket CORS 安全漏洞（0.5 天）
2. ✅ 添加 JsonParser 安全限制（2 天）

### 短期实施（第 2-3 周）

3. ✅ 优化 WebServer 启动检测（1 天）
4. ✅ 优化 BindingIndex 性能（2 天）
5. ✅ 添加 InjectionResolver 作用域验证（2 天）

### 中期实施（第 4-6 周）

6. ✅ 补充核心模块测试（15 天）

---

**报告生成时间**：2026-06-01  
**验证方法**：深入分析调用上下文 + 代码逻辑验证  
**目标**：排除误判，聚焦真实问题的修复
