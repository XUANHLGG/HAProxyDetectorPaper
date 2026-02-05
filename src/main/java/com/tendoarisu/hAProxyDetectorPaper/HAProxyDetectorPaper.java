package com.tendoarisu.hAProxyDetectorPaper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public final class HAProxyDetectorPaper extends JavaPlugin {

    @Override
    public void onEnable() {
        try {
            injectNetty();
            getLogger().info("HAProxyDetector 已成功注入 Netty 流。");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "注入 Netty 时出错: ", e);
        }
    }

    private void injectNetty() throws Exception {
        // 1. 获取 ServerConnection (通过反射从 MinecraftServer 中获取)
        Object serverInstance = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        
        // 寻找 ServerConnection 字段
        Object serverConnection = null;
        
        // 策略1: 针对 1.21 的新类名探测 (ServerConnectionListener)
        Class<?> currentClass = serverInstance.getClass();
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.endsWith("ServerConnectionListener") || typeName.endsWith("ServerConnection")) {
                    field.setAccessible(true);
                    serverConnection = field.get(serverInstance);
                    if (serverConnection != null) break;
                }
            }
            if (serverConnection != null) break;
            currentClass = currentClass.getSuperclass();
        }

        // 策略2: 特征探测兜底 (寻找包含 ChannelFuture 列表的对象)
        if (serverConnection == null) {
            currentClass = serverInstance.getClass();
            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    field.setAccessible(true);
                    try {
                        Object potentialConn = field.get(serverInstance);
                        if (potentialConn == null) continue;

                        for (Field subField : potentialConn.getClass().getDeclaredFields()) {
                            if (List.class.isAssignableFrom(subField.getType())) {
                                subField.setAccessible(true);
                                Object list = subField.get(potentialConn);
                                if (list instanceof List && !((List<?>) list).isEmpty() && ((List<?>) list).get(0) instanceof ChannelFuture) {
                                    serverConnection = potentialConn;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    if (serverConnection != null) break;
                }
                if (serverConnection != null) break;
                currentClass = currentClass.getSuperclass();
            }
        }

        if (serverConnection == null) {
            throw new IllegalStateException("无法在当前服务端版本中定位 ServerConnection 实例");
        }

        // 2. 获取 List<ChannelFuture> (存储所有监听的端口)
        List<ChannelFuture> channelFutures = null;
        
        for (Field field : serverConnection.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object list = field.get(serverConnection);
                if (list instanceof List) {
                    List<?> genericList = (List<?>) list;
                    // 1.21 字段名通常为 'channels'，1.20 通常为 'g'
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

        // 3. 遍历所有监听的 ChannelFuture 并注入 ChannelInitializer
        for (ChannelFuture future : channelFutures) {
            ChannelPipeline pipeline = future.channel().pipeline();
            
            // 检查是否已经注入过
            boolean alreadyInjected = false;
            for (String name : pipeline.names()) {
                if (pipeline.get(name) instanceof HAProxyInjector) {
                    alreadyInjected = true;
                    break;
                }
            }

            if (!alreadyInjected) {
                // 使用匿名注入，确保不会因名称重复导致注入失败
                pipeline.addFirst(new HAProxyInjector());
            }
        }
    }

    /**
     * 内部类用于拦截新连接并注入 HAProxyHandler
     */
    private class HAProxyInjector extends io.netty.channel.ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel) {
                Channel childChannel = (Channel) msg;
                ChannelPipeline cp = childChannel.pipeline();

                // 检查 childChannel 是否已经有我们的 handler 类型
                boolean hasHandler = false;
                for (String name : cp.names()) {
                    if (cp.get(name) instanceof HAProxyHandler) {
                        hasHandler = true;
                        break;
                    }
                }

                if (!hasHandler) {
                    // 核心逻辑：添加到最前端以拦截并识别原始流量特征
                    cp.addFirst(new HAProxyHandler(getLogger()));
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void onDisable() {
        // 插件关闭逻辑
    }
}
