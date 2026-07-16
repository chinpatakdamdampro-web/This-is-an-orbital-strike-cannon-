package com.pvpbot.stabshot.themesong;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pvpbot.stabshot.youtube.YtCommand;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.List;

@Environment(EnvType.CLIENT)
public class ThemeSongCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerDisk(dispatcher);
            YtCommand.register(dispatcher);
        });
    }

    private static void registerDisk(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // /ts play <song>  — play once
        dispatcher.register(
            ClientCommandManager.literal("ts")
                .then(ClientCommandManager.literal("play")
                    .then(ClientCommandManager.argument("song", StringArgumentType.greedyString())
                        .executes(ctx -> execPlay(ctx, false))))
        );

        // /ts loop <song>  — loop (separate command avoids brigadier ambiguity)
        dispatcher.register(
            ClientCommandManager.literal("ts")
                .then(ClientCommandManager.literal("loop")
                    .then(ClientCommandManager.argument("song", StringArgumentType.greedyString())
                        .executes(ctx -> execPlay(ctx, true))))
        );

        // /ts stop
        dispatcher.register(
            ClientCommandManager.literal("ts")
                .then(ClientCommandManager.literal("stop")
                    .executes(ThemeSongCommand::execStop))
        );

        // /ts list
        dispatcher.register(
            ClientCommandManager.literal("ts")
                .then(ClientCommandManager.literal("list")
                    .executes(ThemeSongCommand::execList))
        );
    }

    private static int execPlay(CommandContext<FabricClientCommandSource> ctx, boolean loop) {
        String song = StringArgumentType.getString(ctx, "song");
        String err  = ThemeSongPlayer.play(song, loop);
        if (err != null) {
            ctx.getSource().sendFeedback(Text.literal("§c✘ " + err));
        } else {
            String loopInfo = loop ? " §7(looping — §f/ts stop §7to end)" : " §7(once)";
            ctx.getSource().sendFeedback(Text.literal("§a♪ Now playing: §f" + song + loopInfo));
        }
        return 1;
    }

    private static int execStop(CommandContext<FabricClientCommandSource> ctx) {
        if (!ThemeSongPlayer.isPlaying()) {
            ctx.getSource().sendFeedback(Text.literal("§7No song is currently playing."));
            return 0;
        }
        String  was       = ThemeSongPlayer.getCurrentSong();
        boolean wasLooping = ThemeSongPlayer.isLooping();
        ThemeSongPlayer.stop();
        ctx.getSource().sendFeedback(Text.literal(
            "§7■ Stopped: §f" + was + (wasLooping ? " §7(was looping)" : "")));
        return 1;
    }

    private static int execList(CommandContext<FabricClientCommandSource> ctx) {
        List<String> songs = ThemeSongPlayer.getSongNames();
        if (songs.isEmpty()) {
            ctx.getSource().sendFeedback(Text.literal(
                "§7No songs found. Add §f.ogg §7or §f.mp3 §7files to:\n§f"
                + ThemeSongPlayer.getSongsDir()));
            return 1;
        }
        ctx.getSource().sendFeedback(Text.literal("§6§lSongs (" + songs.size() + "):"));
        songs.forEach(s -> ctx.getSource().sendFeedback(Text.literal("§7 • §f" + s)));
        ctx.getSource().sendFeedback(Text.literal(
            "§7Usage: §f/ts play <name> §7or §f/ts loop <name>"));
        return 1;
    }
}
