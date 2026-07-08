package com.pvpbot.stabshot.spotify;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Thin shared-state bag so the auth thread can surface the login URL
 * to the Minecraft chat command without a circular dependency.
 */
@Environment(EnvType.CLIENT)
public class SpotifyState {

    private static volatile String pendingAuthUrl = null;

    public static void setPendingAuthUrl(String url) {
        pendingAuthUrl = url;
    }

    /** Consumes and returns the pending URL (null if none). */
    public static String consumePendingAuthUrl() {
        String url = pendingAuthUrl;
        pendingAuthUrl = null;
        return url;
    }

    public static boolean hasPendingAuthUrl() {
        return pendingAuthUrl != null;
    }
}
