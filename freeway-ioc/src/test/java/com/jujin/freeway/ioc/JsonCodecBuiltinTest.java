package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.json.JsonCodec;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** JSON is a container capability, not an HttpModule feature. */
class JsonCodecBuiltinTest {

    @Test
    void jsonCodecIsAvailableWithoutHttpModule() {
        try (Container container = Freeway.create()) {
            JsonCodec codec = container.get(JsonCodec.class);
            assertNotNull(codec);
            String quote = String.valueOf((char) 34);
            assertEquals(Map.of("a", 1),
                codec.fromJson("{" + quote + "a" + quote + ":1}", Map.class));
        }
    }
}
