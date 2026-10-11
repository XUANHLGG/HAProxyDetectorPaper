package com.tendoarisu.haproxydetector.common;

@FunctionalInterface
public interface ProxyLogger {
    void warn(String message, Throwable exception);

    default void warn(String message) {
        warn(message, null);
    }

    default Messages messages() {
        return Messages.english();
    }

    static ProxyLogger localized(Messages messages, ProxyLogger sink) {
        return new ProxyLogger() {
            @Override
            public void warn(String message, Throwable exception) {
                sink.warn(message, exception);
            }

            @Override
            public Messages messages() {
                return messages;
            }
        };
    }
}
