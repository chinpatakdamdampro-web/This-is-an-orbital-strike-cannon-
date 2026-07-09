package com.pvpbot.stabshot.spotify;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class SpotifyCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("ts")
            .then(ClientCommandManager.literal("spotify")

                .then(ClientCommandManager.literal("login")
                    .executes(SpotifyCommand::execLogin))

                .then(ClientCommandManager.literal("open")
                    .executes(SpotifyCommand::execOpen))

                .then(ClientCommandManager.literal("url")
                    .executes(SpotifyCommand::execUrl))

                .then(ClientCommandManager.literal("play")
                    .then(ClientCommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(SpotifyCommand::execPlay)))

                .then(ClientCommandManager.literal("uri")
                    .then(ClientCommandManager.argument("uri", StringArgumentType.greedyString())
                        .executes(SpotifyCommand::execUri)))

                .then(ClientCommandManager.literal("pause")
                    .executes(SpotifyCommand::execPause))

                .then(ClientCommandManager.literal("resume")
                    .executes(SpotifyCommand::execResume))

                .then(ClientCommandManager.literal("next")
                    .executes(SpotifyCommand::execNext))

                .then(ClientCommandManager.literal("prev")
                    .executes(SpotifyCommand::execPrev))

                .then(ClientCommandManager.literal("stop")
                    .executes(SpotifyCommand::execStop))

                .then(ClientCommandManager.literal("status")
                    .executes(SpotifyCommand::execStatus))
            )
        );
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------

    private static int execLogin(CommandContext<FabricClientCommandSource> ctx) {
        var src  = ctx.getSource();
        var auth = SpotifyAuth.getInstance();

        if (auth.loadTokens(FabricLoader.getInstance().getConfigDir())) {
            feedback(src, "§a[Spotify] Already logged in! Opening player...");
            openPlayerAndNotify(src);
            return 1;
        }

        feedback(src, "§e[Spotify] Opening Spotify login page...");
        feedback(src, "§7On PojavLauncher: use §b/ts spotify url §7if the browser doesn't open.");

        auth.startLogin().thenRun(() -> {
            auth.saveTokens(FabricLoader.getInstance().getConfigDir());
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                feedback(src, "§a[Spotify] Logged in! Opening player page...");
                openPlayerAndNotify(src);
            });
        }).exceptionally(e -> {
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                feedback(src, "§c[Spotify] Login failed: " + e.getCause().getMessage()));
            return null;
        });

        return 1;
    }

    private static int execOpen(CommandContext<FabricClientCommandSource> ctx) {
        var src = ctx.getSource();
        if (!checkAuth(src)) return 0;
        openPlayerAndNotify(src);
        return 1;
    }

    private static int execUrl(CommandContext<FabricClientCommandSource> ctx) {
        var src = ctx.getSource();
        String authUrl = SpotifyState.consumePendingAuthUrl();
        if (authUrl != null) {
            feedback(src, "§e[Spotify] Click to open login page:");
            feedbackUrl(src, authUrl);
            return 1;
        }
        if (SpotifyAuth.getInstance().isAuthenticated()) {
            feedback(src, "§e[Spotify] Click to open player:");
            feedbackUrl(src, "http://127.0.0.1:" + SpotifyWebServer.PORT + "/player");
        } else {
            feedback(src, "§7[Spotify] Not logged in. Run §b/ts spotify login §7first.");
        }
        return 1;
    }

    private static int execPlay(CommandContext<FabricClientCommandSource> ctx) {
        var src   = ctx.getSource();
        var query = StringArgumentType.getString(ctx, "query");
        if (!checkAuth(src)) return 0;

        feedback(src, "§e[Spotify] Searching: " + query + "...");
        new Thread(() -> {
            String result = SpotifyPlayer.getInstance().playSearch(query);
            net.minecraft.client.MinecraftClient.getInstance().execute(() ->
                feedback(src, "§a" + result));
        }, "SpotifySearch-Thread").start();
        return 1;
    }

    private static int execUri(CommandContext<FabricClientCommandSource> ctx) {
        var src = ctx.getSource();
        var uri = StringArgumentType.getString(ctx, "uri");
        if (!checkAuth(src)) return 0;

        String spotifyUri = toSpotifyUri(uri);
        String result = SpotifyPlayer.getInstance().play(spotifyUri);
        feedback(src, "§a" + result);
        return 1;
    }

    private static int execPause(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§e" + SpotifyPlayer.getInstance().pause());
        return 1;
    }

    private static int execResume(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§a" + SpotifyPlayer.getInstance().resume());
        return 1;
    }

    private static int execNext(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§a" + SpotifyPlayer.getInstance().next());
        return 1;
    }

    private static int execPrev(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§a" + SpotifyPlayer.getInstance().prev());
        return 1;
    }

    private static int execStop(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§e" + SpotifyPlayer.getInstance().stop());
        return 1;
    }

    private static int execStatus(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx.getSource(), "§a" + SpotifyPlayer.getInstance().getStatusString());
        return 1;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void openPlayerAndNotify(FabricClientCommandSource src) {
        SpotifyPlayer.getInstance().openPlayer();
        String url = "http://127.0.0.1:" + SpotifyWebServer.PORT + "/player";
        feedback(src, "§7Click to open the Spotify player:");
        feedbackUrl(src, url);
        feedback(src, "§7Use §f/ts spotify play <song> §7to control playback from Minecraft.");
    }

    /** Sends a clickable URL in chat — clicking it opens the URL in the browser. */
    private static void feedbackUrl(FabricClientCommandSource src, String url) {
        MutableText text = Text.literal("§b§n" + url)
                .setStyle(Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withUnderline(true));
        src.sendFeedback(text);
    }

    private static boolean checkAuth(FabricClientCommandSource src) {
        if (!SpotifyAuth.getInstance().isAuthenticated()) {
            feedback(src, "§c[Spotify] Not logged in. Run §b/ts spotify login §cfirst.");
            return false;
        }
        return true;
    }

    private static String toSpotifyUri(String input) {
        if (input.contains("open.spotify.com/")) {
            String[] parts = input.split("open.spotify.com/")[1].split("\\?")[0].split("/");
            if (parts.length >= 2) return "spotify:" + parts[0] + ":" + parts[1];
        }
        return input;
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }
}
