package com.jujin.freeway.commons.bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.junit.jupiter.api.Test;

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
        assertTrue(title.writable());
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
        assertFalse(name.writable());
        assertThrows(UnsupportedOperationException.class, () -> name.write(new ImmutableBean("x"), "y"));
    }

    @Test
    void filtersStaticAndTransientFields() {
        BeanPlan plan = BeanIntrospector.plan(FieldFiltered.class);

        assertNotNull(plan.property("data"));
        assertFalse(plan.property("data").writable());
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
        assertTrue(prop.writable());

        HasSetter bean = new HasSetter();
        prop.write(bean, "viaSetter");
        assertEquals("set:viaSetter", bean.value);
    }

    @Test
    void nonConstructableBeanIsUnconstructable() {
        BeanPlan plan = BeanIntrospector.plan(NoDefaultConstructor.class);

        assertFalse(plan.constructable());
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

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Marker {
    }
}
