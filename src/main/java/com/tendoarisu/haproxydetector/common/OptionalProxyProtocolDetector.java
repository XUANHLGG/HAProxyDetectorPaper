package com.tendoarisu.haproxydetector.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.ProtocolDetectionState;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import java.util.List;

final class OptionalProxyProtocolDetector extends ByteToMessageDecoder {
    private final String proxyDecoderName;
    private final String sourceValidatorName;

    OptionalProxyProtocolDetector(String proxyDecoderName, String sourceValidatorName) {
        this.proxyDecoderName = proxyDecoderName;
        this.sourceValidatorName = sourceValidatorName;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        ProtocolDetectionResult<HAProxyProtocolVersion> detection =
                HAProxyMessageDecoder.detectProtocol(input);
        if (detection.state() == ProtocolDetectionState.NEEDS_MORE_DATA) {
            return;
        }

        ChannelPipeline pipeline = context.pipeline();
        if (detection.state() == ProtocolDetectionState.INVALID) {
            removeIfPresent(pipeline, proxyDecoderName);
            removeIfPresent(pipeline, sourceValidatorName);
        }

        output.add(input.readRetainedSlice(input.readableBytes()));
        pipeline.remove(this);
    }

    private void removeIfPresent(ChannelPipeline pipeline, String handlerName) {
        if (pipeline.get(handlerName) != null) {
            pipeline.remove(handlerName);
        }
    }
}
