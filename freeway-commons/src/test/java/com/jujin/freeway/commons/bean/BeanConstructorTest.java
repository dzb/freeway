package com.jujin.freeway.commons.bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.junit.jupiter.api.Test;

class BeanConstructorTest {
    @Test
    void exposesConstructorAndParameterAnnotations() throws Throwable {
        BeanConstructor constructor = BeanIntrospector.constructor(Sample.class.getDeclaredConstructor(String.class, int.class));

        assertNotNull(constructor);
        assertTrue(constructor.hasAnnotation(Marker.class));
        assertEquals(2, constructor.parameters().size());
        assertTrue(constructor.parameters().get(0).hasAnnotation(First.class));
        assertTrue(constructor.parameters().get(1).hasAnnotation(Second.class));

        Sample sample = (Sample) constructor.newInstance("alpha", 7);
        assertEquals("alpha", sample.name());
        assertEquals(7, sample.count());
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Marker {
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface First {
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Second {
    }

    private static final class Sample {
        private final String name;
        private final int count;

        @Marker
        private Sample(@First String name, @Second int count) {
            this.name = name;
            this.count = count;
        }

        String name() {
            return name;
        }

        int count() {
            return count;
        }
    }
}
