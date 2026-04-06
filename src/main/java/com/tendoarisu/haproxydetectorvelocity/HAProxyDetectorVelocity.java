package com.tendoarisu.haproxydetectorvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "haproxydetectorvelocity",
        name = "HAProxyDetectorVelocity",
        version = "1.3",
        description = "Allow mixed HAProxy and direct connections for Velocity",
        authors = {"TendoArisu"}
)
public class HAProxyDetectorVelocity {

    private static final String INJECTOR_NAME = "haproxydetectorvelocity-injector";
    private static final String CONNECTION_HANDLER_NAME = "haproxydetectorvelocity-handler";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private boolean whitelistEnabled = true;
    private List<String> whitelist = new ArrayList<>();
    private final Set<Channel> injectedServerChannels = ConcurrentHashMap.newKeySet();
    private final Set<Channel> injectedChildChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean nettyActive = false;

    @Inject
    public HAProxyDetectorVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            loadConfig();
            injectNetty();
            nettyActive = true;
            logger.info("HAProxyDetectorVelocity 已成功注入 Netty 流。");
        } catch (Exception e) {
            nettyActive = false;
            detachNetty();
            logger.error("注入 Velocity Netty 时出错: ", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        nettyActive = false;
        detachNetty();
    }

    private void loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (inputStream == null) {
                    throw new IOException("未找到内置 config.yml");
                }
                Files.copy(inputStream, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        whitelistEnabled = true;
        whitelist = new ArrayList<>();
        boolean readingWhitelist = false;

        for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("enable-whitelist:")) {
                String value = normalizeYamlValue(trimmed.substring("enable-whitelist:".length()).trim());
                whitelistEnabled = Boolean.parseBoolean(value);
                readingWhitelist = false;
                continue;
            }

            if (trimmed.equals("whitelist:")) {
                readingWhitelist = true;
                continue;
            }

            if (readingWhitelist) {
                if (trimmed.startsWith("-")) {
                    String entry = normalizeYamlValue(trimmed.substring(1).trim());
                    if (!entry.isEmpty()) {
                        whitelist.add(entry);
                    }
                    continue;
                }

                if (!Character.isWhitespace(line.charAt(0))) {
                    readingWhitelist = false;
                }
            }
        }

        whitelist = resolveWhitelistEntries(whitelist);
    }

    private String stripComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (current == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (current == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private String normalizeYamlValue(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private List<String> resolveWhitelistEntries(List<String> entries) {
        List<String> resolvedEntries = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }

            String normalized = entry.trim();
            if (normalized.isEmpty()) {
                continue;
            }

            if (normalized.contains("/")) {
                resolvedEntries.add(normalized);
                continue;
            }

            try {
                for (InetAddress address : InetAddress.getAllByName(normalized)) {
                    resolvedEntries.add(address.getHostAddress());
                }
            } catch (Exception ignored) {
                resolvedEntries.add(normalized);
            }
        }
        return resolvedEntries;
    }

    private void injectNetty() throws Exception {
        Object cm = null;
        for (Field field : server.getClass().getDeclaredFields()) {
            if (field.getType().getSimpleName().contains("ConnectionManager")) {
                field.setAccessible(true);
                cm = field.get(server);
                break;
            }
        }

        if (cm == null) {
            return;
        }

        for (Field field : cm.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                List<?> list = (List<?>) field.get(cm);
                if (list != null && !list.isEmpty() && list.get(0) instanceof ChannelFuture) {
                    List<ChannelFuture> futures = (List<ChannelFuture>) list;
                    for (ChannelFuture future : futures) {
                        Channel serverChannel = future.channel();
                        if (serverChannel.pipeline().get(INJECTOR_NAME) == null) {
                            serverChannel.pipeline().addFirst(INJECTOR_NAME, new ServerInjectHandler());
                        }
                        trackChannel(injectedServerChannels, serverChannel);
                    }
                    break;
                }
            }
        }
    }

    private void trackChannel(Set<Channel> channelSet, Channel channel) {
        if (channelSet.add(channel)) {
            channel.closeFuture().addListener(future -> channelSet.remove(channel));
        }
    }

    private void detachNetty() {
        for (Channel channel : new ArrayList<>(injectedChildChannels)) {
            removeHandler(channel, CONNECTION_HANDLER_NAME);
        }
        injectedChildChannels.clear();

        for (Channel channel : new ArrayList<>(injectedServerChannels)) {
            removeHandler(channel, INJECTOR_NAME);
        }
        injectedServerChannels.clear();
    }

    private void removeHandler(Channel channel, String handlerName) {
        if (channel == null) {
            return;
        }

        Runnable removeTask = () -> {
            try {
                if (channel.pipeline().get(handlerName) != null) {
                    channel.pipeline().remove(handlerName);
                }
            } catch (Exception ignored) {
            }
        };

        try {
            if (channel.eventLoop().inEventLoop()) {
                removeTask.run();
            } else {
                channel.eventLoop().execute(removeTask);
            }
        } catch (Exception ignored) {
        }
    }

    private class ServerInjectHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!nettyActive) {
                removeHandler(ctx.channel(), INJECTOR_NAME);
                super.channelRead(ctx, msg);
                return;
            }

            if (msg instanceof Channel childChannel) {
                if (childChannel.pipeline().get(CONNECTION_HANDLER_NAME) == null) {
                    childChannel.pipeline().addFirst(CONNECTION_HANDLER_NAME, new HAProxyHandler(logger, whitelistEnabled, whitelist));
                    trackChannel(injectedChildChannels, childChannel);
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    public static class HAProxyHandler extends ChannelInboundHandlerAdapter {
        private final Logger logger;
        private final boolean whitelistEnabled;
        private final List<String> whitelist;
        private static final AttributeKey<Boolean> SYNTHETIC_PROXY_MARK = AttributeKey.valueOf("haproxydetectorvelocity.synthetic-proxy");
        private static final byte[] V2_SIG = {
                0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
        };

        public HAProxyHandler(Logger logger, boolean whitelistEnabled, List<String> whitelist) {
            this.logger = logger;
            this.whitelistEnabled = whitelistEnabled;
            this.whitelist = new ArrayList<>(whitelist);
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
                        if (consumeSyntheticProxyMark(ctx)) {
                            ctx.pipeline().remove(this);
                        } else {
                            SocketAddress remoteAddr = ctx.channel().remoteAddress();
                            String frpsIp = getSocketIp(remoteAddr);
                            if (whitelistEnabled && !isWhitelisted(frpsIp)) {
                                String clientIp = extractProxyClientIp(buf);
                                logger.warn("拦截非白名单 frps 连接: frps={}, client={}", frpsIp, clientIp);
                                ctx.close();
                                return;
                            }
                            ctx.pipeline().remove(this);
                        }
                    } else {
                        SocketAddress remoteAddr = ctx.channel().remoteAddress();
                        if (remoteAddr instanceof InetSocketAddress inetAddr) {
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
}
