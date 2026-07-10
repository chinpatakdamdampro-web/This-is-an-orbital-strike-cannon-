package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays MP3 and OGG files via javax.sound.sampled + JLayer directly.
 * Does NOT go through Minecraft's SoundManager or OpenAL at all.
 * This ensures audio works on Zalith/PojavLauncher on Android where
 * OpenAL-based playback is often completely silent due to the custom LWJGL build.
 */
@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";

    private static volatile Thread         playThread;
    private static volatile SourceDataLine currentLine;
    private static volatile String         currentSong;
    private static volatile boolean        playing  = false;
    private static volatile boolean        looping  = false;
    private static final AtomicBoolean     loopActive = new AtomicBoolean(false);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static String play(final String name, final boolean loop) {
        stop();

        final Path songsDir = getSongsDir();
        Path   file = null;
        String ext  = null;
        for (String e : new String[]{"mp3", "ogg"}) {
            Path candidate = songsDir.resolve(name + "." + e);
            if (Files.exists(candidate)) { file = candidate; ext = e; break; }
        }
        if (file == null) {
            return "§cSong not found: §f" + name + ".ogg §7or §f" + name + ".mp3\n"
                 + "§7Put audio files in: §f" + songsDir + "\n"
                 + "§7Available: §f" + String.join(", ", getSongNames());
        }

        final Path   fFile = file;
        final String fExt  = ext;
        currentSong = name;
        looping     = loop;
        loopActive.set(true);

        playThread = new Thread(() -> {
            try {
                do {
                    playing = true;
                    if ("mp3".equals(fExt)) playMp3(fFile);
                    else                    playOgg(fFile);
                } while (loop && loopActive.get() && !Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[StabShot] Playback error: " + e.getMessage());
            } finally {
                playing = false;
                if (!loopActive.get()) currentSong = null;
            }
        }, "StabShot-PlayThread");
        playThread.setDaemon(true);
        playThread.start();
        return null;
    }

    /** Used by YtPlayer to play a cached audio file. YtPlayer manages its own loop. */
    public static void playFile(final Path file) {
        stop();
        currentSong = file.getFileName().toString();
        looping     = false;
        loopActive.set(true);
        playing     = true;

        playThread = new Thread(() -> {
            try {
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".mp3")) playMp3(file);
                else                       playOgg(file);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[StabShot] playFile error: " + e.getMessage());
            } finally {
                playing = false;
            }
        }, "StabShot-FilePlayThread");
        playThread.setDaemon(true);
        playThread.start();
    }

    public static void stop() {
        loopActive.set(false);
        playing = false;
        looping = false;

        // Stop the audio line first so the thread unblocks from line.write()
        SourceDataLine line = currentLine;
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
            currentLine = null;
        }

        // Then interrupt the thread
        Thread t = playThread;
        if (t != null) {
            t.interrupt();
            playThread = null;
        }

        currentSong = null;
    }

    public static boolean isPlaying()      { return playing; }
    public static boolean isLooping()      { return looping; }
    public static String  getCurrentSong() { return currentSong; }

    public static List<String> getSongNames() {
        List<String> names = new ArrayList<>();
        Path dir = getSongsDir();
        if (!Files.exists(dir)) return names;
        File[] files = dir.toFile().listFiles(f -> {
            String n = f.getName().toLowerCase();
            return f.isFile() && (n.endsWith(".mp3") || n.endsWith(".ogg"));
        });
        if (files == null) return names;
        for (File f : files) {
            String n   = f.getName();
            int    dot = n.lastIndexOf('.');
            names.add(dot > 0 ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static Path getSongsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SONGS_FOLDER);
        try { if (!Files.exists(dir)) Files.createDirectories(dir); }
        catch (Exception ignored) {}
        return dir;
    }

    // -----------------------------------------------------------------------
    // Internal — MP3 via JLayer decoded to PCM → javax.sound.sampled
    // -----------------------------------------------------------------------

    private static void playMp3(Path file) throws Exception {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(file))) {
            Bitstream bitstream = new Bitstream(fis);
            Decoder   decoder   = new Decoder();
            SourceDataLine line = null;

            try {
                while (loopActive.get() && !Thread.currentThread().isInterrupted()) {
                    Header header = bitstream.readFrame();
                    if (header == null) break;

                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    // Open audio line on first frame (we now know sample rate + channels)
                    if (line == null) {
                        int         sr  = output.getSampleFrequency();
                        int         ch  = output.getChannelCount();
                        AudioFormat fmt = new AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED,
                                sr, 16, ch, ch * 2, sr, false);
                        line = (SourceDataLine) AudioSystem.getLine(
                                new DataLine.Info(SourceDataLine.class, fmt));
                        line.open(fmt);
                        line.start();
                        currentLine = line;
                    }

                    // Convert shorts → little-endian bytes and write to audio line
                    short[] samples = output.getBuffer();
                    int     count   = output.getBufferLength();
                    byte[]  bytes   = new byte[count * 2];
                    for (int i = 0; i < count; i++) {
                        bytes[i * 2]     = (byte) (samples[i] & 0xFF);
                        bytes[i * 2 + 1] = (byte) (samples[i] >> 8 & 0xFF);
                    }
                    line.write(bytes, 0, bytes.length);
                    bitstream.closeFrame();
                }
            } finally {
                if (line != null) {
                    try { line.drain(); line.stop(); line.close(); }
                    catch (Exception ignored) {}
                }
                currentLine = null;
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal — OGG via javax.sound.sampled (decoded by the JVM audio stack)
    // -----------------------------------------------------------------------

    private static void playOgg(Path file) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat baseFormat   = raw.getFormat();
            AudioFormat decodeFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(), 16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(), false);

            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(decodeFormat, raw)) {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                        new DataLine.Info(SourceDataLine.class, decodeFormat));
                line.open(decodeFormat);
                line.start();
                currentLine = line;

                byte[] buf = new byte[4096];
                int n;
                while (loopActive.get()
                        && !Thread.currentThread().isInterrupted()
                        && (n = decoded.read(buf, 0, buf.length)) != -1) {
                    line.write(buf, 0, n);
                }

                try { line.drain(); line.stop(); line.close(); }
                catch (Exception ignored) {}
                currentLine = null;
            }
        }
    }
}
