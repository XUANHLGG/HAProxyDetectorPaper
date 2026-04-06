package com.tendoarisu.haproxydetectorpaper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class HAProxyDetectorPaper extends JavaPlugin {

    private static final String INJECTOR_NAME = "haproxydetectorpaper-injector";
    private static final String CONNECTION_HANDLER_NAME = "haproxydetectorpaper-handler";

    private boolean proxyProtocolEnabled = false;
    private boolean whitelistEnabled = true;
    private List<String> whitelist = new ArrayList<>();
    private final Set<Channel> injectedServerChannels = ConcurrentHashMap.newKeySet();
    private final Set<Channel> injectedChildChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean nettyActive = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();

        try {
            checkProxyProtocol();
            if (!proxyProtocolEnabled) {
                getLogger().warning("检测到 Paper 未开启 proxy-protocol，插件将自动禁用。");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }

            injectNetty();
            nettyActive = true;
            getLogger().info("HAProxyDetector 已成功注入 Netty 流。");
            if (!whitelistEnabled) {
                getLogger().warning("注意：白名单检查已禁用，当前处于调试模式（允许所有 HAProxy 连接）。");
            }
        } catch (Exception e) {
            nettyActive = false;
            detachNetty();
            getLogger().log(Level.SEVERE, "注入 Netty 时出错: ", e);
        }
    }

    private void loadPluginConfig() {
        reloadConfig();
        whitelistEnabled = getConfig().getBoolean("enable-whitelist", true);
        whitelist = resolveWhitelistEntries(getConfig().getStringList("whitelist"));
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

    private void checkProxyProtocol() {
        try {
            Class<?> configClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Method getMethod = configClass.getMethod("get");
            Object configInstance = getMethod.invoke(null);

            Field proxiedField = configInstance.getClass().getField("proxies");
            Object proxiesObj = proxiedField.get(configInstance);

            Field proxyProtocolField = proxiesObj.getClass().getField("proxyProtocol");
            proxyProtocolEnabled = proxyProtocolField.getBoolean(proxiesObj);
        } catch (Exception e) {
            proxyProtocolEnabled = true;
        }
    }

    private void injectNetty() throws Exception {
        Object serverInstance = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());

        Object serverConnection = null;
        Class<?> currentClass = serverInstance.getClass();
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.endsWith("ServerConnectionListener") || typeName.endsWith("ServerConnection")) {
                    field.setAccessible(true);
                    serverConnection = field.get(serverInstance);
                    if (serverConnection != null) {
                        break;
                    }
                }
            }
            if (serverConnection != null) {
                break;
            }
            currentClass = currentClass.getSuperclass();
        }

        if (serverConnection == null) {
            currentClass = serverInstance.getClass();
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    field.setAccessible(true);
                    try {
                        Object potentialConn = field.get(serverInstance);
                        if (potentialConn == null) {
                            continue;
                        }

                        for (Field subField : potentialConn.getClass().getDeclaredFields()) {
                            if (List.class.isAssignableFrom(subField.getType())) {
                                subField.setAccessible(true);
                                Object list = subField.get(potentialConn);
                                if (list instanceof List<?> genericList && !genericList.isEmpty() && genericList.get(0) instanceof ChannelFuture) {
                                    serverConnection = potentialConn;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (serverConnection != null) {
                        break;
                    }
                }
                if (serverConnection != null) {
                    break;
                }
                currentClass = currentClass.getSuperclass();
            }
        }

        if (serverConnection == null) {
            throw new IllegalStateException("无法在当前服务端版本中定位 ServerConnection 实例");
        }

        List<ChannelFuture> channelFutures = null;
        for (Field field : serverConnection.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object list = field.get(serverConnection);
                if (list instanceof List<?> genericList) {
                    if (!genericList.isEmpty() && genericList.get(0) instanceof ChannelFuture) {
                        channelFutures = (List<ChannelFuture>) list;
                        break;
                    }
                    if (genericList.isEmpty() && (field.getName().equals("channels") || field.getName().equals("g"))) {
                        channelFutures = (List<ChannelFuture>) list;
                    }
                }
            }
        }

        if (channelFutures == null) {
            return;
        }

        for (ChannelFuture future : channelFutures) {
            Channel serverChannel = future.channel();
            ChannelPipeline pipeline = serverChannel.pipeline();
            if (pipeline.get(INJECTOR_NAME) == null) {
                pipeline.addFirst(INJECTOR_NAME, new HAProxyInjector());
            }
            trackChannel(injectedServerChannels, serverChannel);
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
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(handlerName) != null) {
                    pipeline.remove(handlerName);
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

    private class HAProxyInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!nettyActive) {
                removeHandler(ctx.channel(), INJECTOR_NAME);
                super.channelRead(ctx, msg);
                return;
            }

            if (msg instanceof Channel childChannel) {
                ChannelPipeline pipeline = childChannel.pipeline();
                if (pipeline.get(CONNECTION_HANDLER_NAME) == null) {
                    pipeline.addFirst(CONNECTION_HANDLER_NAME, new HAProxyHandler(getLogger(), whitelistEnabled, whitelist));
                    trackChannel(injectedChildChannels, childChannel);
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void onDisable() {
        nettyActive = false;
        detachNetty();
    }
}
