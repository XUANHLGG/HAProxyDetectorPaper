package com.tendoarisu.hAProxyDetectorPaper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.logging.Logger;

/**
 * HAProxy 协议注入器
 * 当 Paper 开启 proxy-protocol: true 时：
 * 1. 如果检测到已有 HAProxy 头（frp 玩家），则直接放行。
 * 2. 如果检测到无头（直连玩家），则伪造一个 HAProxy V2 头注入，欺骗 Paper 的解码器。
 */
public class HAProxyHandler extends ChannelInboundHandlerAdapter {
    private final Logger logger;
    
    // HAProxy V2 签名
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
                    // 已经是 HAProxy 协议，放行让 Paper 处理
                    ctx.pipeline().remove(this);
                } else if (res == 0) {
                    // 直连玩家，伪造一个 HAProxy V2 头部
                    
                    SocketAddress remoteAddr = ctx.channel().remoteAddress();
                    if (remoteAddr instanceof InetSocketAddress) {
                        InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
                        ByteBuf fakeHeader = createV2Header(inetAddr);
                        
                        // 将伪造头和原始数据组合
                        ByteBuf combined = Unpooled.wrappedBuffer(fakeHeader, buf.retain());
                        
                        // 移除自己，避免循环处理
                        ctx.pipeline().remove(this);
                        
                        // 发送组合后的数据包给下一个 Handler (Paper 的 HAProxy 解码器)
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

    /**
     * 创建 HAProxy V2 协议头
     */
    private ByteBuf createV2Header(InetSocketAddress addr) {
        ByteBuf header = Unpooled.buffer(28);
        header.writeBytes(V2_SIG);
        header.writeByte(0x21); // Ver 2 | Cmd PROXY
        header.writeByte(0x11); // AF_INET (IPv4) | STREAM (TCP)
        header.writeShort(12);  // 剩余长度 (4+4+2+2 = 12)
        
        // 源地址填玩家真实 IP，目标地址填 127.0.0.1 以确保通过 Paper 的安全校验
        byte[] ip = addr.getAddress().getAddress();
        header.writeBytes(ip);  // Source IP
        header.writeBytes(new byte[]{127, 0, 0, 1}); // Dest IP (Localhost)
        header.writeShort(addr.getPort()); // Source Port
        header.writeShort(25565); // Dest Port
        
        return header;
    }

    private int isHAProxy(ByteBuf buf) {
        int readerIndex = buf.readerIndex();
        int readableBytes = buf.readableBytes();

        // V1 Check
        if (readableBytes >= 6) {
            if (buf.getByte(readerIndex) == 'P' &&
                buf.getByte(readerIndex + 1) == 'R' &&
                buf.getByte(readerIndex + 2) == 'O' &&
                buf.getByte(readerIndex + 3) == 'X' &&
                buf.getByte(readerIndex + 4) == 'Y' &&
                buf.getByte(readerIndex + 5) == ' ') {
                return 1;
            }
        }

        // V2 Check
        if (readableBytes >= 12) {
            for (int i = 0; i < V2_SIG.length; i++) {
                if (buf.getByte(readerIndex + i) != V2_SIG[i]) {
                    return 0;
                }
            }
            return 1;
        }

        return 0;
    }
}
