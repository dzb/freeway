package com.jujin.freeway.http.event;

/** Published on the EventBus when an HTTP exchange results in an unhandled exception. */
public record HttpErrorEvent(String method, String path, Exception exception) {}
