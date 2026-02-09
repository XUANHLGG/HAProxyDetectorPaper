package com.tendoarisu.hAProxyDetectorVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

@Plugin(
        id = "haproxydetectorvelocity",
        name = "HAProxyDetectorVelocity",
        version = "1.1",
        description = "Allow mixed HAProxy and direct connections for Velocity",
        authors = {"TendoArisu"}
)
public class HAProxyDetectorVelocity {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public HAProxyDetectorVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            injectNetty();
            logger.info("HAProxyDetectorVelocity 已成功注入 Netty 流。");
        } catch (Exception e) {
            logger.error("注入 Velocity Netty 时出错: ", e);
        }
    }

    private void injectNetty() throws Exception {
        // Velocity 的监听逻辑通常在 ConnectionManager 中
        // 我们需要找到底层监听的 ChannelFuture
        Object cm = null;
        for (Field field : server.getClass().getDeclaredFields()) {
            if (field.getType().getSimpleName().contains("ConnectionManager")) {
                field.setAccessible(true);
                cm = field.get(server);
                break;
            }
        }

        if (cm == null) return;

        // 在 ConnectionManager 中寻找监听的 ChannelFuture 列表
        for (Field field : cm.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                List<?> list = (List<?>) field.get(cm);
                if (list != null && !list.isEmpty() && list.get(0) instanceof ChannelFuture) {
                    List<ChannelFuture> futures = (List<ChannelFuture>) list;
                    for (ChannelFuture future : futures) {
                        future.channel().pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (msg instanceof Channel) {
                                    Channel child = (Channel) msg;
                                    child.pipeline().addFirst(new HAProxyHandler(logger));
                                }
                                super.channelRead(ctx, msg);
                            }
                        });
                    }
                    break;
                }
            }
        }
    }

    public static class HAProxyHandler extends ChannelInboundHandlerAdapter {
        private final Logger logger;
        private static final byte[] V2_SIG = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
        };

        public HAProxyHandler(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                if (buf.readableBytes() < 6) {
                    super.channelRead(ctx, msg);
                    return;
                }

                buf.markReaderIndex();
                try {
                    int res = isHAProxy(buf);
                    if (res == 1) {
                        ctx.pipeline().remove(this);
                    } else if (res == 0) {
                        SocketAddress remoteAddr = ctx.channel().remoteAddress();
                        if (remoteAddr instanceof InetSocketAddress) {
                            InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
                            ByteBuf fakeHeader = createV2Header(inetAddr);
                            ByteBuf combined = Unpooled.wrappedBuffer(fakeHeader, buf.retain());
                            ctx.pipeline().remove(this);
                            ctx.fireChannelRead(combined);
                            return;
                        }
                        ctx.pipeline().remove(this);
                    }
                } finally {
                    buf.resetReaderIndex();
                }
            }
            super.channelRead(ctx, msg);
        }

        private int isHAProxy(ByteBuf buf) {
            int readerIndex = buf.readerIndex();
            int readableBytes = buf.readableBytes();
            if (readableBytes >= 6) {
                if (buf.getByte(readerIndex) == 'P' && buf.getByte(readerIndex + 1) == 'R' &&
                        buf.getByte(readerIndex + 2) == 'O' && buf.getByte(readerIndex + 3) == 'X' &&
                        buf.getByte(readerIndex + 4) == 'Y' && buf.getByte(readerIndex + 5) == ' ') {
                    return 1;
                }
            }
            if (readableBytes >= 12) {
                for (int i = 0; i < V2_SIG.length; i++) {
                    if (buf.getByte(readerIndex + i) != V2_SIG[i]) return 0;
                }
                return 1;
            }
            return 0;
        }

        private ByteBuf createV2Header(InetSocketAddress addr) {
            ByteBuf header = Unpooled.buffer(28);
            header.writeBytes(V2_SIG);
            header.writeByte(0x21);
            header.writeByte(0x11);
            header.writeShort(12);
            byte[] ip = addr.getAddress().getAddress();
            header.writeBytes(ip);
            header.writeBytes(new byte[]{127, 0, 0, 1});
            header.writeShort(addr.getPort());
            header.writeShort(25565);
            return header;
        }
    }
}
