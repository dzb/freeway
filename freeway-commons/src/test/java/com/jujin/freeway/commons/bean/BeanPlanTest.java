package com.jujin.freeway.commons.bean;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanPlanTest {
    @Test
    void recordPlanExposesConstructorAndProperties() {
        BeanPlan plan = BeanIntrospector.plan(Point.class);

        assertTrue(plan.record());
        assertEquals(Point.class, plan.type());
        assertNotNull(plan.constructor());
        assertEquals(2, plan.properties().size());
        assertEquals("x", plan.property("x").name());
        assertEquals("name", plan.property("name").name());
        assertNotNull(plan.property("x").annotation(Marker.class));
        assertNotNull(plan.property("name").annotation(Marker.class));

        Point point = (Point) plan.constructor().newInstance(3, "alpha");
        assertEquals(3, plan.property("x").read(point));
        assertEquals("alpha", plan.property("name").read(point));
    }

    @Test
    void beanPlanExposesWritableFieldAndAnnotations() {
        BeanPlan plan = BeanIntrospector.plan(SampleBean.class);

        assertFalse(plan.record());
        assertEquals(SampleBean.class, plan.type());
        assertNotNull(plan.constructor());
        assertEquals(2, plan.properties().size());

        BeanProperty title = plan.property("title");
        assertNotNull(title);
        assertTrue(title.isWritable());
        assertNotNull(title.annotation(Marker.class));

        SampleBean bean = (SampleBean) plan.constructor().newInstance();
        title.write(bean, "hello");
        assertEquals("hello", title.read(bean));
    }

    @Test
    void finalFieldWithoutSetterIsReadOnly() {
        BeanPlan plan = BeanIntrospector.plan(ImmutableBean.class);

        BeanProperty name = plan.property("name");
        assertNotNull(name);
        assertFalse(name.isWritable());
        assertThrows(UnsupportedOperationException.class, () -> name.write(new ImmutableBean("x"), "y"));
    }

    @Test
    void filtersStaticAndTransientFields() {
        BeanPlan plan = BeanIntrospector.plan(FieldFiltered.class);

        assertNotNull(plan.property("data"));
        assertFalse(plan.property("data").isWritable());
        assertNull(plan.property("transientThing"));
        assertNull(plan.property("CONSTANT"));
    }

    @Test
    void inheritsFieldsFromSuperlass() {
        BeanPlan plan = BeanIntrospector.plan(ChildBean.class);

        assertNotNull(plan.property("parentName"));
        assertNotNull(plan.property("childAge"));
    }

    @Test
    void setterMethodOverridesFieldWrite() {
        BeanPlan plan = BeanIntrospector.plan(HasSetter.class);

        BeanProperty prop = plan.property("value");
        assertTrue(prop.isWritable());

        HasSetter bean = new HasSetter();
        prop.write(bean, "viaSetter");
        assertEquals("set:viaSetter", bean.value);
    }

    @Test
    void nonConstructableBeanIsUnisConstructable() {
        BeanPlan plan = BeanIntrospector.plan(NoDefaultConstructor.class);

        assertFalse(plan.isConstructable());
    }

    private record Point(@Marker int x, @Marker String name) {
    }

    private static final class SampleBean {
        @Marker
        private String title;

        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    private static final class ImmutableBean {
        final String name;

        ImmutableBean(String name) {
            this.name = name;
        }
    }

    private static final class FieldFiltered {
        static final String CONSTANT = "C";
        final String data = "ok";
        transient int transientThing = 0;
    }

    private static class ParentBean {
        private String parentName;
    }

    private static final class ChildBean extends ParentBean {
        private int childAge;

        public int getChildAge() {
            return childAge;
        }

        public void setChildAge(int childAge) {
            this.childAge = childAge;
        }
    }

    private static final class HasSetter {
        private String value;

        public void setValue(String value) {
            this.value = "set:" + value;
        }
    }

    public static final class NoDefaultConstructor {
        public NoDefaultConstructor(String name) {
        }
    }

    // ====================== regression fixes ======================

    @Test
    void planOnClassWithJdkSuperclassDoesNotThrow() {
        // Regression: the superclass walk used to descend into java.base
        // classes (Throwable etc.) where no VarHandle can be created,
        // throwing RuntimeException for custom exceptions.
        BeanPlan plan = BeanIntrospector.plan(JdkSubclassEntity.class);
        assertEquals(1, plan.properties().size());
        assertEquals("code", plan.properties().get(0).name());
    }

    @Test
    void planOnJdkClassWithPublicConstructorSucceeds() {
        // Regression: createConstructorHandle threw on java.base classes
        // ("module java.base does not open java.lang") — the lookup now falls
        // back to publicLookup, so JDK types introspect to a zero-property
        // plan instead of failing.
        BeanPlan plan = BeanIntrospector.plan(Object.class);
        assertTrue(plan.isConstructable());
        assertEquals(0, plan.properties().size());
    }

    @Test
    void jdkTypePlanContractMatrix() {
        // Module-access contract: plan() on JDK types never throws — the
        // lookup falls back to publicLookup for public members, JDK fields
        // are not introspected, and the resulting plan carries deterministic
        // semantics for each shape.
        BeanPlan object = BeanIntrospector.plan(Object.class);
        assertTrue(object.isConstructable());
        assertEquals(0, object.properties().size());

        BeanPlan arrayList = BeanIntrospector.plan(ArrayList.class);
        assertTrue(arrayList.isConstructable(),
            "public no-arg constructor must be reachable via publicLookup");
        assertEquals(0, arrayList.properties().size(),
            "JDK fields must not be introspected");

        BeanPlan integer = BeanIntrospector.plan(Integer.class);
        assertFalse(integer.isConstructable(),
            "no no-arg constructor -> non-constructable, not an error");
        assertEquals(0, integer.properties().size());

        BeanPlan throwableSubclass = BeanIntrospector.plan(
            JdkSubclassEntity.class
        );
        assertEquals(1, throwableSubclass.properties().size(),
            "app fields of a JDK subclass are introspected, JDK superclass fields are not");
        assertEquals("code", throwableSubclass.properties().get(0).name());
    }

    static class JdkSubclassEntity extends RuntimeException {
        String code;
    }

    // ====================== selectConstructor ======================

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Preferred {
    }

    static class PrefersAnnotated {
        PrefersAnnotated() {}

        @Preferred
        PrefersAnnotated(String a, int b) {}
    }

    static class MultiplePreferred {
        @Preferred
        MultiplePreferred(String a) {}

        @Preferred
        MultiplePreferred(String a, int b) {}
    }

    static class NoArgPreferred {
        NoArgPreferred() {}

        NoArgPreferred(String a, int b) {}
    }

    static class MaxParamsOnly {
        MaxParamsOnly(String a) {}

        MaxParamsOnly(String a, int b, long c) {}
    }

    static class PrivateNoArg {
        private PrivateNoArg() {}
    }

    @Test
    void selectConstructorPrefersAnnotated() throws NoSuchMethodException {
        BeanConstructor selected = BeanIntrospector.selectConstructor(
            PrefersAnnotated.class, Preferred.class
        );
        assertEquals(2, selected.constructor().getParameterCount(),
            "the @Preferred constructor must win over the no-arg one");
    }

    @Test
    void selectConstructorRejectsMultipleAnnotated() {
        assertThrows(IllegalArgumentException.class,
            () -> BeanIntrospector.selectConstructor(
                MultiplePreferred.class, Preferred.class));
    }

    @Test
    void selectConstructorPrefersNoArgOverMaxParams() throws NoSuchMethodException {
        BeanConstructor selected = BeanIntrospector.selectConstructor(
            NoArgPreferred.class, Preferred.class
        );
        assertEquals(0, selected.constructor().getParameterCount());
    }

    @Test
    void selectConstructorUsesMaxParamsWhenNoNoArg() throws NoSuchMethodException {
        BeanConstructor selected = BeanIntrospector.selectConstructor(
            MaxParamsOnly.class, Preferred.class
        );
        assertEquals(3, selected.constructor().getParameterCount());
    }

    @Test
    void selectConstructorSupportsPrivateNoArg() throws NoSuchMethodException {
        BeanConstructor selected = BeanIntrospector.selectConstructor(
            PrivateNoArg.class, Preferred.class
        );
        assertEquals(0, selected.constructor().getParameterCount());
        assertNotNull(selected.newInstance(),
            "private constructor handle must be invocable");
    }

    @Test
    void selectConstructorRejectsNonInstantiableTypes() {
        assertThrows(NoSuchMethodException.class,
            () -> BeanIntrospector.selectConstructor(Preferred.class, Preferred.class));
        assertThrows(NoSuchMethodException.class,
            () -> BeanIntrospector.selectConstructor(int[].class, Preferred.class));
        assertThrows(NoSuchMethodException.class,
            () -> BeanIntrospector.selectConstructor(int.class, Preferred.class));
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Marker {
    }
}
