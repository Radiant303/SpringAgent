package com.spring.gateway.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索服务厂商目录。
 *
 * @author hanbing
 * @since 2026-10-17
 */
public final class SearchProviders {
    public static final Map<String, String> ALL;
    public static final String LOCAL_ENABLED_KEY = "search.local.enabled";
    public static final String LOCAL_PROVIDER_KEY = "search.local.provider";
    public static final String NATIVE_POINTS_KEY = "search.native.points";
    public static final String DEFAULT_PROVIDER = "tavily";

    static {
        Map<String, String> providers = new LinkedHashMap<>();
        providers.put("tavily", "Tavily");
        providers.put("exa", "Exa");
        ALL = Collections.unmodifiableMap(providers);
    }

    private SearchProviders() {
    }

    public static boolean isSupported(String provider) {
        return ALL.containsKey(provider);
    }

    public static String apiKeySetting(String provider) {
        requireSupported(provider);
        return "search.providers." + provider + ".api_key";
    }

    public static String pointsSetting(String provider) {
        if ("native".equals(provider)) {
            return NATIVE_POINTS_KEY;
        }
        requireSupported(provider);
        return "search.providers." + provider + ".points";
    }

    private static void requireSupported(String provider) {
        if (!isSupported(provider)) {
            throw new BizException(400, "未知搜索厂商: " + provider);
        }
    }
}
