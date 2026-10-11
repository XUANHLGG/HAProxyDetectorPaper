package com.tendoarisu.haproxydetector.common;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

public final class ProxyProtocolSupport {
    public static final String DETECTOR_NAME = "haproxydetector-detector";
    public static final String VALIDATOR_NAME = "haproxydetector-source-validator";

    private ProxyProtocolSupport() {
    }

    public static void install(ChannelPipeline pipeline, IpWhitelist whitelist, ProxyLogger logger) {
        try {
            ChannelHandlerContext decoder = pipeline.context(HAProxyMessageDecoder.class);
            if (decoder == null) {
                throw new IllegalStateException(logger.messages().get(MessageKey.NATIVE_DECODER_MISSING));
            }

            // Check the socket source before the platform adopts the address in the decoded header.
            pipeline.addAfter(decoder.name(), VALIDATOR_NAME, new ProxySourceValidator(whitelist, logger));
            pipeline.addBefore(decoder.name(), DETECTOR_NAME,
                    new OptionalProxyProtocolDetector(decoder.name(), VALIDATOR_NAME));
        } catch (RuntimeException exception) {
            logger.warn(logger.messages().get(MessageKey.NETTY_INJECTION_FAILED), exception);
            throw exception;
        }
    }
}
