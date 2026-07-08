package com.pvpbot.stabshot.client;

import com.pvpbot.stabshot.spotify.SpotifyWebServer;
import com.pvpbot.stabshot.themesong.ThemeSongCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class StabShotClient implements ClientModInitializer
{
    public void onInitializeClient() {
        ThemeSongCommand.register();

        // Start the Spotify web server (port 7891) so the player page
        // is ready as soon as the user logs in
        try {
            SpotifyWebServer.getInstance().start();
        } catch (Exception e) {
            System.err.println("[StabShot] Could not start Spotify web server: " + e.getMessage());
        }
    }
}
