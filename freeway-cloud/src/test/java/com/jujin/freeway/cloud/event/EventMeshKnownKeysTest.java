package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.registration.RegistrationModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EventMeshKnownKeysTest {

    @Test
    void declaresTheIdentityKeysItsOriginReads() {
        try (Container container = Freeway.create(new EventMeshModule())) {
            Set<String> keys = container.extension(KnownKeys.class).all().stream()
                .flatMap(known -> known.keys().stream())
                .collect(Collectors.toSet());
            assertTrue(keys.contains(CloudModule.ConfigKeys.APP_NAME),
                "the mesh origin reads freeway.app.name");
            assertTrue(keys.contains(RegistrationModule.ConfigKeys.REGISTRY_SERVICE_ID),
                "the mesh origin reads the registration service id");
        }
    }
}
