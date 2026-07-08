package com.pvpbot.stabshot.themesong;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pvpbot.stabshot.spotify.SpotifyCommand;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.class_2561;

import java.util.List;

@Environment(EnvType.CLIENT)
public class ThemeSongCommand
{
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Original /ts play / stop / list commands — unchanged
            dispatcher.register(
                (LiteralArgumentBuilder) ((LiteralArgumentBuilder) ((LiteralArgumentBuilder)
                    ClientCommandManager.literal("ts")
                        .then(((LiteralArgumentBuilder) ClientCommandManager.literal("play")
                            .then(ClientCommandManager.literal("loop")
                                .then(ClientCommandManager.argument("song", (ArgumentType) StringArgumentType.greedyString())
                                    .executes(ctx -> execPlay((CommandContext<FabricClientCommandSource>) ctx, true)))))
                            .then(ClientCommandManager.argument("song", (ArgumentType) StringArgumentType.greedyString())
                                .executes(ctx -> execPlay((CommandContext<FabricClientCommandSource>) ctx, false)))))
                    .then(ClientCommandManager.literal("stop").executes(ThemeSongCommand::execStop)))
                    .then(ClientCommandManager.literal("list").executes(ThemeSongCommand::execList))
            );

            // Spotify integration — /ts spotify ...
            SpotifyCommand.register(dispatcher);
        });
    }

    private static int execPlay(final CommandContext<FabricClientCommandSource> ctx, final boolean loop) {
        final String song = StringArgumentType.getString(ctx, "song");
        final String err = ThemeSongPlayer.play(song, loop);
        if (err != null) {
            ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§c✘ " + err));
        } else {
            final String loopInfo = loop ? " §7(looping — §f/ts stop §7to end)" : " §7(once)";
            ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§a♪ Now playing: §f" + song + loopInfo));
        }
        return 1;
    }

    private static int execStop(final CommandContext<FabricClientCommandSource> ctx) {
        if (!ThemeSongPlayer.isPlaying()) {
            ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§7No song is currently playing."));
            return 0;
        }
        final String was = ThemeSongPlayer.getCurrentSong();
        final boolean wasLooping = ThemeSongPlayer.isLooping();
        ThemeSongPlayer.stop();
        ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§7■ Stopped: §f" + was + (wasLooping ? " §7(was looping)" : "")));
        return 1;
    }

    private static int execList(final CommandContext<FabricClientCommandSource> ctx) {
        final List<String> songs = ThemeSongPlayer.getSongNames();
        if (songs.isEmpty()) {
            ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§7No songs found. Add §f.ogg §7or §f.mp3 §7files to:\n§f" + ThemeSongPlayer.getSongsDir()));
            return 1;
        }
        ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§6§lSongs (" + songs.size() + "):"));
        songs.forEach(s -> ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§7 • §f" + s)));
        ctx.getSource().sendFeedback((class_2561) class_2561.method_43470("§7Usage: §f/ts play [loop] <name>"));
        return 1;
    }
                                     }
