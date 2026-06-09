package com.jujin.freeway.ioc;

import org.slf4j.Logger;

public interface LoggerSource {

    Logger get(Class<?> ownerType);

    Logger get(String name);
}
