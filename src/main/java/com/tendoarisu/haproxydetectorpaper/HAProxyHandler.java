package com.tendoarisu.haproxydetectorpaper;

import com.tendoarisu.haproxydetector.common.IpWhitelist;
import com.tendoarisu.haproxydetector.common.ProxyLogger;
import com.tendoarisu.haproxydetector.common.ProxyProtocolSupport;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.local.LocalChannel;
import io.netty.util.ReferenceCountUtil;

/** Installs shared handlers after Paper has initialized the child pipeline. */
final class HAProxyHandler extends ChannelInboundHandlerAdapter {
    private final IpWhitelist whitelist;
    private final ProxyLogger logger;

    HAProxyHandler(IpWhitelist whitelist, ProxyLogger logger) {
        this.whitelist = whitelist;
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (context.channel() instanceof LocalChannel) {
            context.pipeline().remove(this);
            context.fireChannelRead(message);
            return;
        }

        try {
            ProxyProtocolSupport.install(context.pipeline(), whitelist, logger);
        } catch (Exception exception) {
            ReferenceCountUtil.release(message);
            context.close();
            throw exception;
        }
        context.pipeline().remove(this);
        context.fireChannelRead(message);
    }
}
