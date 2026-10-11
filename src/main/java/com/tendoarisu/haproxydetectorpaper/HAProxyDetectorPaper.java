package com.tendoarisu.haproxydetectorpaper;

import com.tendoarisu.haproxydetector.common.IpWhitelist;
import com.tendoarisu.haproxydetector.common.MessageKey;
import com.tendoarisu.haproxydetector.common.Messages;
import com.tendoarisu.haproxydetector.common.ProxyLogger;
import com.tendoarisu.haproxydetector.common.ProxyProtocolSupport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private IpWhitelist whitelist;
    private Messages messages = Messages.system();
    private ProxyLogger proxyLogger;
    private final Set<Channel> injectedServerChannels = ConcurrentHashMap.newKeySet();
    private final Set<Channel> injectedChildChannels = ConcurrentHashMap.newKeySet();
    private volatile boolean nettyActive = false;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            loadPluginConfig();
            checkProxyProtocol();
            if (!proxyProtocolEnabled) {
                getLogger().warning(messages.get(MessageKey.PROXY_DISABLED, "Paper", "proxy-protocol"));
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }

            nettyActive = true;
            injectNetty();
            getLogger().info(messages.get(MessageKey.NETTY_INJECTED));
            if (!whitelistEnabled) {
                getLogger().warning(messages.get(MessageKey.WHITELIST_DISABLED));
            }
        } catch (Exception e) {
            nettyActive = false;
            detachNetty();
            getLogger().log(Level.SEVERE, messages.get(MessageKey.NETTY_INJECTION_FAILED), e);
        }
    }

    private void loadPluginConfig() {
        reloadConfig();
        String language = getConfig().getString("language", Messages.DEFAULT_LANGUAGE);
        messages = Messages.select(language,
                (message, exception) -> getLogger().log(Level.WARNING, message, exception));
        if (Messages.needsDetection(language)) {
            getConfig().set("language", messages.language());
            saveConfig();
        }
        proxyLogger = ProxyLogger.localized(messages,
                (message, exception) -> getLogger().log(Level.WARNING, message, exception));
        whitelistEnabled = getConfig().getBoolean("enable-whitelist", true);
        whitelist = IpWhitelist.resolve(whitelistEnabled, getConfig().getStringList("whitelist"), proxyLogger);
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
            getLogger().log(Level.WARNING, messages.get(MessageKey.PAPER_PROXY_CONFIG_FAILED), e);
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
                    } catch (Exception exception) {
                        getLogger().log(Level.WARNING, messages.get(MessageKey.PAPER_CONNECTION_PROBE_FAILED), exception);
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
            throw new IllegalStateException(messages.get(MessageKey.PAPER_CONNECTION_MISSING));
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

        if (channelFutures == null || channelFutures.isEmpty()) {
            throw new IllegalStateException(messages.get(MessageKey.PAPER_LISTENER_MISSING));
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
            removeHandler(channel, ProxyProtocolSupport.DETECTOR_NAME);
            removeHandler(channel, ProxyProtocolSupport.VALIDATOR_NAME);
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
            } catch (Exception exception) {
                getLogger().log(Level.WARNING, messages.get(MessageKey.NETTY_REMOVAL_FAILED), exception);
            }
        };

        try {
            if (channel.eventLoop().inEventLoop()) {
                removeTask.run();
            } else {
                channel.eventLoop().execute(removeTask);
            }
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, messages.get(MessageKey.NETTY_REMOVAL_FAILED), exception);
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
                    pipeline.addFirst(CONNECTION_HANDLER_NAME, new HAProxyHandler(whitelist, proxyLogger));
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
