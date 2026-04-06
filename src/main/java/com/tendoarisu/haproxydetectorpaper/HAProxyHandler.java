package com.tendoarisu.haproxydetectorpaper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

public class HAProxyHandler extends ChannelInboundHandlerAdapter {
    private final Logger logger;
    private final boolean whitelistEnabled;
    private final List<String> whitelist;

    private static final AttributeKey<Boolean> SYNTHETIC_PROXY_MARK = AttributeKey.valueOf("haproxydetectorpaper.synthetic-proxy");
    private static final byte[] V2_SIG = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };

    public HAProxyHandler(Logger logger, boolean whitelistEnabled, List<String> whitelist) {
        this.logger = logger;
        this.whitelistEnabled = whitelistEnabled;
        this.whitelist = whitelist;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        String className = ctx.channel().getClass().getName();
        if (className.contains("LocalChannel") || className.contains("EmbeddedChannel")) {
            ctx.pipeline().remove(this);
            super.channelRead(ctx, msg);
            return;
        }

        SocketAddress remoteAddr = ctx.channel().remoteAddress();
        if (remoteAddr == null || remoteAddr.toString().contains("local")) {
            ctx.pipeline().remove(this);
            super.channelRead(ctx, msg);
            return;
        }

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
                    if (consumeSyntheticProxyMark(ctx)) {
                        ctx.pipeline().remove(this);
                    } else {
                        String frpsIp = getSocketIp(remoteAddr);
                        if (whitelistEnabled && !isWhitelisted(frpsIp)) {
                            String clientIp = extractProxyClientIp(buf);
                            logger.warning("拦截非白名单 frps 连接: frps=" + frpsIp + ", client=" + clientIp);
                            ctx.close();
                            return;
                        }

                        ctx.pipeline().remove(this);
                    }
                } else {
                    if (isGeyser(buf)) {
                        ctx.pipeline().remove(this);
                        super.channelRead(ctx, msg);
                        return;
                    }

                    if (remoteAddr instanceof InetSocketAddress) {
                        InetSocketAddress inetAddr = (InetSocketAddress) remoteAddr;
                        ByteBuf fakeHeader = createV2Header(inetAddr);
                        ByteBuf combined = Unpooled.wrappedBuffer(fakeHeader, buf.retain());
                        ctx.channel().attr(SYNTHETIC_PROXY_MARK).set(Boolean.TRUE);
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

    private boolean consumeSyntheticProxyMark(ChannelHandlerContext ctx) {
        return Boolean.TRUE.equals(ctx.channel().attr(SYNTHETIC_PROXY_MARK).getAndSet(null));
    }

    private ByteBuf createV2Header(InetSocketAddress addr) {
        boolean isIPv6 = addr.getAddress() instanceof java.net.Inet6Address;
        int addressLen = isIPv6 ? 32 : 12;

        ByteBuf header = Unpooled.buffer(16 + addressLen);
        header.writeBytes(V2_SIG);
        header.writeByte(0x21);

        if (isIPv6) {
            header.writeByte(0x21);
            header.writeShort(36);
            header.writeBytes(addr.getAddress().getAddress());
            header.writeBytes(new byte[16]);
            header.setByte(header.writerIndex() - 1, 1);
        } else {
            header.writeByte(0x11);
            header.writeShort(12);
            header.writeBytes(addr.getAddress().getAddress());
            header.writeBytes(new byte[]{127, 0, 0, 1});
        }

        header.writeShort(addr.getPort());
        header.writeShort(25565);
        return header;
    }

    private boolean isWhitelisted(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        for (String entry : whitelist) {
            if (entry.contains("/")) {
                if (matchCIDR(ip, entry)) {
                    return true;
                }
            } else if (entry.equalsIgnoreCase(ip)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchCIDR(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);

            InetAddress addr = InetAddress.getByName(ip);
            InetAddress netAddr = InetAddress.getByName(network);

            byte[] addrBytes = addr.getAddress();
            byte[] netBytes = netAddr.getAddress();

            if (addrBytes.length != netBytes.length) {
                return false;
            }

            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != netBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                return (addrBytes[fullBytes] & mask) == (netBytes[fullBytes] & mask);
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int isHAProxy(ByteBuf buf) {
        int readerIndex = buf.readerIndex();
        int readableBytes = buf.readableBytes();

        if (matchesV1Signature(buf, readerIndex, readableBytes)) {
            return 1;
        }

        if (matchesV2Signature(buf, readerIndex, readableBytes)) {
            return 1;
        }

        return 0;
    }

    private boolean isGeyser(ByteBuf buf) {
        int readableBytes = buf.readableBytes();
        if (readableBytes < 1) {
            return false;
        }

        int firstByte = buf.getByte(buf.readerIndex()) & 0xFF;
        if (firstByte == 0xFE) {
            return true;
        }

        if (readableBytes >= 2) {
            int packetId = buf.getByte(buf.readerIndex() + 1) & 0xFF;
            if (firstByte > 0 && packetId != 0x00) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesV1Signature(ByteBuf buf, int readerIndex, int readableBytes) {
        return readableBytes >= 6
            && buf.getByte(readerIndex) == 'P'
            && buf.getByte(readerIndex + 1) == 'R'
            && buf.getByte(readerIndex + 2) == 'O'
            && buf.getByte(readerIndex + 3) == 'X'
            && buf.getByte(readerIndex + 4) == 'Y'
            && buf.getByte(readerIndex + 5) == ' ';
    }

    private boolean matchesV2Signature(ByteBuf buf, int readerIndex, int readableBytes) {
        if (readableBytes < 12) {
            return false;
        }

        for (int i = 0; i < V2_SIG.length; i++) {
            if (buf.getByte(readerIndex + i) != V2_SIG[i]) {
                return false;
            }
        }

        return true;
    }

    private String getSocketIp(SocketAddress remoteAddr) {
        if (remoteAddr instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            if (address != null) {
                return address.getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }
        return "unknown";
    }

    private String extractProxyClientIp(ByteBuf buf) {
        int readerIndex = buf.readerIndex();
        int readableBytes = buf.readableBytes();

        if (matchesV1Signature(buf, readerIndex, readableBytes)) {
            int lineEnd = findLineEnd(buf, readerIndex, readableBytes);
            int length = lineEnd == -1 ? readableBytes : lineEnd - readerIndex;
            String header = buf.toString(readerIndex, length, StandardCharsets.US_ASCII).trim();
            String[] parts = header.split("\\s+");
            if (parts.length >= 3) {
                return parts[2];
            }
            return "unknown";
        }

        if (!matchesV2Signature(buf, readerIndex, readableBytes) || readableBytes < 16) {
            return "unknown";
        }

        try {
            int versionCommand = buf.getUnsignedByte(readerIndex + 12);
            if ((versionCommand >> 4) != 0x2) {
                return "unknown";
            }

            int familyProtocol = buf.getUnsignedByte(readerIndex + 13);
            int headerLength = buf.getUnsignedShort(readerIndex + 14);
            if (readableBytes < 16 + headerLength) {
                return "unknown";
            }

            int family = familyProtocol & 0xF0;
            if (family == 0x10) {
                if (headerLength < 12) {
                    return "unknown";
                }
                byte[] source = new byte[4];
                buf.getBytes(readerIndex + 16, source);
                return InetAddress.getByAddress(source).getHostAddress();
            }

            if (family == 0x20) {
                if (headerLength < 36) {
                    return "unknown";
                }
                byte[] source = new byte[16];
                buf.getBytes(readerIndex + 16, source);
                return InetAddress.getByAddress(source).getHostAddress();
            }
        } catch (Exception ignored) {
            return "unknown";
        }

        return "unknown";
    }

    private int findLineEnd(ByteBuf buf, int readerIndex, int readableBytes) {
        int maxIndex = readerIndex + readableBytes - 1;
        for (int i = readerIndex; i < maxIndex; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
