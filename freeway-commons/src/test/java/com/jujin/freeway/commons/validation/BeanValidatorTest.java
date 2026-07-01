package com.jujin.freeway.commons.validation;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BeanValidatorTest {

    // --- Test POJOs ---

    static class LoginRequest {
        @NotBlank
        String username;

        @NotNull
        @Size(min = 6, max = 64)
        String password;
    }

    static class CreateUserRequest {
        @NotBlank
        String name;

        @NotNull
        @Min(1)
        @Max(150)
        Integer age;

        @Size(max = 10)
        String tag;

        @Valid
        Address address;
    }

    static class Address {
        @NotBlank
        String city;
    }

    static class CyclicNode {
        @NotBlank
        String name;

        @Valid
        CyclicNode next;
    }

    // --- Tests ---

    @Test
    void validObject() {
        var req = new LoginRequest();
        req.username = "alice";
        req.password = "secure123";
        var result = BeanValidator.validate(req);
        assertFalse(result.hasErrors());
    }

    @Test
    void notBlankViolation() {
        var req = new LoginRequest();
        req.username = "   ";
        req.password = "secure123";
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        assertEquals("username", result.getErrors().get(0).field());
        assertTrue(result.getErrors().get(0).message().contains("blank"));
    }

    @Test
    void notNullViolation() {
        var result = BeanValidator.validate(new LoginRequest());
        assertTrue(result.hasErrors());
        // null username -> NotBlank fires; null password -> NotNull + Size(min=6) both fire
        assertEquals(3, result.getErrors().size());
    }

    @Test
    void sizeViolationTooShort() {
        var req = new LoginRequest();
        req.username = "bob";
        req.password = "123";
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertEquals("password", result.getErrors().get(0).field());
    }

    @Test
    void sizeViolationTooLong() {
        var req = new LoginRequest();
        req.username = "bob";
        req.password = "a".repeat(65);
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
    }

    @Test
    void minViolation() {
        var req = new CreateUserRequest();
        req.name = "Alice";
        req.age = 0;
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().equals("age")));
    }

    @Test
    void maxViolation() {
        var req = new CreateUserRequest();
        req.name = "Alice";
        req.age = 200;
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
    }

    @Test
    void nestedValid() {
        var req = new CreateUserRequest();
        req.name = "Alice";
        req.age = 25;
        req.address = new Address();
        req.address.city = "";
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().equals("address.city")));
    }

    @Test
    void cyclicGraphDoesNotOverflow() {
        var node = new CyclicNode();
        node.name = "root";
        node.next = node;

        var result = BeanValidator.validate(node);

        assertFalse(result.hasErrors());
    }

    @Test
    void sizeMaxOnCollection() {
        var req = new CreateUserRequest();
        req.name = "Alice";
        req.age = 25;
        req.tag = "too-long-tag-value";
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().equals("tag")));
    }

    @Test
    void isAnnotated() {
        assertTrue(BeanValidator.isAnnotated(LoginRequest.class));
        assertTrue(BeanValidator.isAnnotated(CreateUserRequest.class));
        assertFalse(BeanValidator.isAnnotated(String.class));
    }

    @Test
    void nullBean() {
        var result = BeanValidator.validate(null);
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        assertEquals("(root)", result.getErrors().get(0).field());
    }

    // --- Record tests (BeanPlan introspection path) ---

    record LoginRecord(@NotBlank String username, @NotNull @Size(min = 6, max = 64) String password) {}

    record UserRecord(@NotBlank String name, @NotNull @Min(1) @Max(150) int age) {}

    record AddressRecord(@NotBlank String city) {}

    record NestedRecord(@Valid AddressRecord address) {}

    @Test
    void recordValid() {
        var req = new LoginRecord("alice", "secure123");
        assertFalse(BeanValidator.validate(req).hasErrors());
    }

    @Test
    void recordNotBlankViolation() {
        var req = new LoginRecord("   ", "secure123");
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertEquals(1, result.getErrors().size());
        assertEquals("username", result.getErrors().get(0).field());
    }

    @Test
    void recordNotNullAndSizeViolation() {
        var req = new LoginRecord(null, null);
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertEquals(3, result.getErrors().size()); // null username → NotBlank + null password → NotNull + Size
    }

    @Test
    void recordMinViolation() {
        var result = BeanValidator.validate(new UserRecord("Alice", 0));
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().equals("age")));
    }

    @Test
    void recordNestedValid() {
        var req = new NestedRecord(new AddressRecord(""));
        var result = BeanValidator.validate(req);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().equals("address.city")));
    }

    @Test
    void recordIsAnnotated() {
        assertTrue(BeanValidator.isAnnotated(LoginRecord.class));
        assertTrue(BeanValidator.isAnnotated(UserRecord.class));
    }

    // --- @Valid with containers ---

    static class OrderItem {
        @NotBlank String name;
        @Min(1) int quantity;
    }

    static class Order {
        @Valid List<OrderItem> items;
    }

    static class MapOrder {
        @Valid java.util.Map<String, OrderItem> entries;
    }

    static class ArrayOrder {
        @Valid OrderItem[] tags;
    }

    static class OrderWithNullItems {
        @Valid List<OrderItem> items;
    }

    @Test
    void validatesCollectionElements() {
        Order order = new Order();
        order.items = List.of(new OrderItem() {{ name = ""; quantity = 0; }});

        var result = BeanValidator.validate(order);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().startsWith("items[0]")));
    }

    @Test
    void validatesMapValues() {
        MapOrder order = new MapOrder();
        order.entries = java.util.Map.of("a", new OrderItem() {{ name = ""; quantity = 0; }});

        var result = BeanValidator.validate(order);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().contains(".a")));
    }

    @Test
    void validatesArrayElements() {
        ArrayOrder order = new ArrayOrder();
        order.tags = new OrderItem[]{new OrderItem() {{ name = ""; quantity = 0; }}};

        var result = BeanValidator.validate(order);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.field().startsWith("tags[0]")));
    }

    static class MapSized {
        @Size(min = 1, max = 3)
        java.util.Map<String, String> entries;
    }

    @Test
    void sizeValidatesMapSize() {
        MapSized obj = new MapSized();
        obj.entries = java.util.Map.of();

        var result = BeanValidator.validate(obj);
        assertTrue(result.hasErrors(),
                "empty Map should fail @Size(min=1): " + result.getErrors());

        obj.entries = java.util.Map.of("a", "1", "b", "2", "c", "3", "d", "4");
        result = BeanValidator.validate(obj);
        assertTrue(result.hasErrors(),
                "4-entry Map should fail @Size(max=3): " + result.getErrors());
    }

    @Test
    void skipsNullElementsInContainers() {
        OrderWithNullItems order = new OrderWithNullItems();
        order.items = new java.util.ArrayList<>();
        order.items.add(null);
        order.items.add(new OrderItem() {{ name = "ok"; quantity = 1; }});

        var result = BeanValidator.validate(order);
        assertFalse(result.hasErrors(), "null elements should be skipped: " + result.getErrors());
    }
}
