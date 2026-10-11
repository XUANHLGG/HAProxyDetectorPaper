package com.tendoarisu.haproxydetectorvelocity;

import com.google.inject.Inject;
import com.tendoarisu.haproxydetector.common.IpWhitelist;
import com.tendoarisu.haproxydetector.common.LanguageConfig;
import com.tendoarisu.haproxydetector.common.MessageKey;
import com.tendoarisu.haproxydetector.common.Messages;
import com.tendoarisu.haproxydetector.common.ProxyLogger;
import com.tendoarisu.haproxydetector.common.ProxyProtocolSupport;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

@Plugin(
        id = "haproxydetectorvelocity",
        name = "HAProxyDetectorVelocity",
        version = "1.5",
        description = "Accept trusted HAProxy connections and ordinary direct connections on one port",
        authors = {"TendoArisu"}
)
public final class HAProxyDetectorVelocity {

    private static final String DELEGATE_INITIALIZER = "haproxydetectorvelocity-delegate";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private Messages messages = Messages.system();
    private ProxyLogger proxyLogger;

    private Object initializerHolder;
    private ChannelInitializer<Channel> originalInitializer;
    private ChannelInitializer<Channel> wrappedInitializer;
    private Method holderGetMethod;
    private Method holderSetMethod;

    @Inject
    public HAProxyDetectorVelocity(
            ProxyServer server,
            Logger logger,
            @DataDirectory Path dataDirectory
    ) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            PluginConfig config = loadConfig();
            messages = Messages.select(config.language(), logger::warn);
            proxyLogger = ProxyLogger.localized(messages, logger::warn);
            if (!isProxyProtocolEnabled()) {
                logger.warn(messages.get(MessageKey.PROXY_DISABLED, "Velocity", "haproxy-protocol"));
                return;
            }
            IpWhitelist whitelist = IpWhitelist.resolve(config.whitelistEnabled(), config.whitelist(), proxyLogger);
            installInitializerWrapper(whitelist);
            logger.info(messages.get(MessageKey.NETTY_INJECTED));
            if (!config.whitelistEnabled()) {
                logger.warn(messages.get(MessageKey.WHITELIST_DISABLED));
            }
        } catch (Exception exception) {
            restoreInitializer();
            logger.error(messages.get(MessageKey.NETTY_INJECTION_FAILED), exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        restoreInitializer();
    }

    private boolean isProxyProtocolEnabled() throws ReflectiveOperationException {
        Object configuration = server.getConfiguration();
        Method isProxyProtocol = configuration.getClass().getMethod("isProxyProtocol");
        return (boolean) isProxyProtocol.invoke(configuration);
    }

    private void installInitializerWrapper(IpWhitelist whitelist) throws ReflectiveOperationException {
        Object connectionManager = findConnectionManager();
        Method getInitializerHolder = connectionManager.getClass().getMethod("getServerChannelInitializer");
        Object holder = getInitializerHolder.invoke(connectionManager);

        Method getMethod = holder.getClass().getMethod("get");
        Method setMethod = holder.getClass().getMethod("set", ChannelInitializer.class);
        Object currentInitializer = getMethod.invoke(holder);
        if (!(currentInitializer instanceof ChannelInitializer<?> channelInitializer)) {
            throw new IllegalStateException(messages.get(MessageKey.VELOCITY_INITIALIZER_INVALID));
        }

        @SuppressWarnings("unchecked")
        ChannelInitializer<Channel> typedInitializer = (ChannelInitializer<Channel>) channelInitializer;
        ChannelInitializer<Channel> wrapper = new MixedProxyChannelInitializer(typedInitializer, whitelist);

        this.initializerHolder = holder;
        this.originalInitializer = typedInitializer;
        this.wrappedInitializer = wrapper;
        this.holderGetMethod = getMethod;
        this.holderSetMethod = setMethod;

        setMethod.invoke(holder, wrapper);
    }

    private Object findConnectionManager() throws ReflectiveOperationException {
        Class<?> currentClass = server.getClass();
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (!field.getType().getSimpleName().equals("ConnectionManager")) {
                    continue;
                }

                field.setAccessible(true);
                Object connectionManager = field.get(server);
                if (connectionManager != null) {
                    return connectionManager;
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        throw new IllegalStateException(messages.get(MessageKey.VELOCITY_CONNECTION_MANAGER_MISSING));
    }

    private void restoreInitializer() {
        if (initializerHolder == null || originalInitializer == null || wrappedInitializer == null
                || holderGetMethod == null || holderSetMethod == null) {
            return;
        }

        try {
            Object current = holderGetMethod.invoke(initializerHolder);
            if (current == wrappedInitializer) {
                holderSetMethod.invoke(initializerHolder, originalInitializer);
            }
        } catch (ReflectiveOperationException exception) {
            logger.warn(messages.get(MessageKey.NETTY_REMOVAL_FAILED), exception);
        } finally {
            initializerHolder = null;
            originalInitializer = null;
            wrappedInitializer = null;
            holderGetMethod = null;
            holderSetMethod = null;
        }
    }

    private PluginConfig loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (inputStream == null) {
                    throw new IOException(messages.get(MessageKey.CONFIG_RESOURCE_MISSING));
                }
                Files.copy(inputStream, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        boolean whitelistEnabled = true;
        String language = Messages.DEFAULT_LANGUAGE;
        int languageLine = -1;
        List<String> whitelist = new ArrayList<>();
        boolean readingWhitelist = false;

        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            String trimmed = stripComment(line).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("language:")) {
                language = normalizeYamlValue(trimmed.substring("language:".length()).trim());
                languageLine = lineIndex;
                readingWhitelist = false;
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

        if (Messages.needsDetection(language)) {
            language = Messages.system().language();
            LanguageConfig.saveDetectedLanguage(configPath, lines, languageLine, language);
        }
        return new PluginConfig(language, whitelistEnabled, whitelist);
    }

    private String stripComment(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (current == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (current == '#' && !inSingleQuote && !inDoubleQuote) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private String normalizeYamlValue(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private final class MixedProxyChannelInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> delegate;
        private final IpWhitelist whitelist;

        private MixedProxyChannelInitializer(ChannelInitializer<Channel> delegate, IpWhitelist whitelist) {
            this.delegate = delegate;
            this.whitelist = whitelist;
        }

        @Override
        protected void initChannel(Channel channel) {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(DELEGATE_INITIALIZER, delegate);

            ProxyProtocolSupport.install(pipeline, whitelist, proxyLogger);
        }
    }

    private record PluginConfig(String language, boolean whitelistEnabled, List<String> whitelist) {
    }
}
