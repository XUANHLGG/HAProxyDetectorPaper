package com.tendoarisu.haproxydetector.common;

public enum MessageKey {
    NETTY_INJECTED("netty.injected"),
    NETTY_INJECTION_FAILED("netty.injection-failed"),
    NETTY_REMOVAL_FAILED("netty.removal-failed"),
    PROXY_DISABLED("proxy.disabled"),
    WHITELIST_DISABLED("whitelist.disabled"),
    WHITELIST_INVALID_ENTRY("whitelist.invalid-entry"),
    SOURCE_RESOLUTION_FAILED("proxy.source-resolution-failed"),
    PROXY_BLOCKED("proxy.blocked"),
    NATIVE_DECODER_MISSING("proxy.native-decoder-missing"),
    INVALID_PREFIX("whitelist.invalid-prefix"),
    PAPER_PROXY_CONFIG_FAILED("paper.proxy-config-read-failed"),
    PAPER_CONNECTION_PROBE_FAILED("paper.connection-structure-probe-failed"),
    PAPER_CONNECTION_MISSING("paper.connection-missing"),
    PAPER_LISTENER_MISSING("paper.listener-missing"),
    VELOCITY_INITIALIZER_INVALID("velocity.initializer-invalid"),
    VELOCITY_CONNECTION_MANAGER_MISSING("velocity.connection-manager-missing"),
    CONFIG_RESOURCE_MISSING("config.resource-missing"),
    UNSUPPORTED_LANGUAGE("config.language-unsupported");

    private final String resourceKey;

    MessageKey(String resourceKey) {
        this.resourceKey = resourceKey;
    }

    public String resourceKey() {
        return resourceKey;
    }
}
