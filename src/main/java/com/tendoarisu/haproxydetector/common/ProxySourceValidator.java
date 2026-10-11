package com.tendoarisu.haproxydetector.common;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;

final class ProxySourceValidator extends ChannelInboundHandlerAdapter {
    private final IpWhitelist whitelist;
    private final ProxyLogger logger;
    private boolean rejected;

    ProxySourceValidator(IpWhitelist whitelist, ProxyLogger logger) {
        this.whitelist = whitelist;
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (rejected) {
            ReferenceCountUtil.release(message);
            return;
        }
        if (!(message instanceof HAProxyMessage proxyMessage)) {
            super.channelRead(context, message);
            return;
        }

        SocketAddress upstreamAddress = context.channel().remoteAddress();
        if (!whitelist.matches(upstreamAddress)) {
            rejected = true;
            String clientIp = proxyMessage.sourceAddress();
            logger.warn(logger.messages().get(MessageKey.PROXY_BLOCKED, IpWhitelist.describe(upstreamAddress),
                    clientIp == null || clientIp.isEmpty() ? "unknown" : clientIp));
            ReferenceCountUtil.release(message);
            context.close();
            return;
        }

        context.pipeline().remove(this);
        context.fireChannelRead(message);
    }
}
