package com.jujin.freeway.http.staticfile;

import java.nio.file.Path;

public final class StaticResources {
    private StaticResources() {
    }

    public static StaticResourceMount directory(String mountPath, Path root) {
        return StaticResourceMount.directory(mountPath, root);
    }

    public static StaticResourceMount classpath(String mountPath, String resourceRoot) {
        return StaticResourceMount.classpath(mountPath, resourceRoot);
    }
}
