package com.tendoarisu.haproxydetector.common;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class Messages {
    public static final String DEFAULT_LANGUAGE = "";
    private static final Messages ENGLISH = new Messages(Locale.US);
    private static final Messages CHINESE = new Messages(Locale.SIMPLIFIED_CHINESE);

    private final Locale locale;
    private final ResourceBundle bundle;

    private Messages(Locale locale) {
        this.locale = locale;
        this.bundle = ResourceBundle.getBundle("messages", locale, Messages.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static Messages english() {
        return ENGLISH;
    }

    public static Messages system() {
        return system(Locale.getDefault());
    }

    static Messages system(Locale locale) {
        return "zh".equalsIgnoreCase(locale.getLanguage()) ? CHINESE : ENGLISH;
    }

    public static boolean needsDetection(String language) {
        return language == null || language.isBlank();
    }

    public String language() {
        return locale.toString();
    }

    public static Messages select(String language, ProxyLogger logger) {
        return select(language, Locale.getDefault(), logger);
    }

    static Messages select(String language, Locale systemLocale, ProxyLogger logger) {
        Messages systemDefault = system(systemLocale);
        if (language == null || language.isBlank()) {
            return systemDefault;
        }
        return switch (language.trim().replace('-', '_').toLowerCase(Locale.ROOT)) {
            case "en_us" -> ENGLISH;
            case "zh_cn" -> CHINESE;
            default -> {
                logger.warn(systemDefault.get(MessageKey.UNSUPPORTED_LANGUAGE, language,
                        systemDefault.language()));
                yield systemDefault;
            }
        };
    }

    public String get(MessageKey key, Object... arguments) {
        // MessageFormat instances are not thread-safe; each call gets its own formatter.
        return new MessageFormat(bundle.getString(key.resourceKey()), locale).format(arguments);
    }
}
