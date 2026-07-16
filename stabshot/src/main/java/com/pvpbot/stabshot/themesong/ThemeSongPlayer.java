package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import javax.sound.sampled.AudioFormat;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";
    private static final String TAG = "[StabShot/Audio]";
    public  static volatile boolean DEBUG = false;

    private static volatile String     currentSong;
    private static volatile boolean    playing  = false;
    private static volatile boolean    looping  = false;
    private static volatile Thread     playThread;
    private static volatile StabSoundInstance currentInstance;
    private static final AtomicBoolean loopActive = new AtomicBoolean(false);

    private static final Identifier DUMMY_ID    = Identifier.of("stabshot", "themesong");
    private static final SoundEvent DUMMY_EVENT = SoundEvent.of(DUMMY_ID);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static String play(final String name, final boolean loop) {
        log("play() called: name=" + name + " loop=" + loop);
        stop();

        final Path songsDir = getSongsDir();
        log("Songs dir: " + songsDir);

        Path   file = null;
        String ext  = null;
        for (String e : new String[]{"mp3", "ogg"}) {
            Path c = songsDir.resolve(name + "." + e);
            log("Checking for file: " + c + " exists=" + Files.exists(c));
            if (Files.exists(c)) { file = c; ext = e; break; }
        }
        if (file == null) {
            log("ERROR: Song file not found for: " + name);
            return "§cSong not found: §f" + name + ".ogg §7or §f" + name + ".mp3\n"
                 + "§7Put audio files in: §f" + songsDir + "\n"
                 + "§7Available: §f" + String.join(", ", getSongNames());
        }

        log("Found file: " + file + " ext=" + ext);
        startPlayback(file, ext, name, loop);
        return null;
    }

    public static void playFile(final Path file) {
        log("playFile() called: " + file);
        stop();
        String name = file.getFileName().toString();
        String ext  = name.endsWith(".ogg") ? "ogg" : "mp3";
        startPlayback(file, ext, name, false);
    }

    public static void stop() {
        log("stop() called — was playing=" + playing + " looping=" + looping);
        loopActive.set(false);
        playing = false;
        looping = false;

        Thread t = playThread;
        if (t != null) {
            t.interrupt();
            playThread = null;
            log("Play thread interrupted");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        StabSoundInstance inst = currentInstance;
        if (inst != null && client != null) {
            client.execute(() -> {
                log("Stopping sound instance via SoundManager");
                client.getSoundManager().stop(inst);
            });
            currentInstance = null;
        }
        currentSong = null;
    }

    public static boolean isPlaying()      { return playing; }
    public static boolean isLooping()      { return looping; }
    public static String  getCurrentSong() { return currentSong; }

    public static List<String> getSongNames() {
        List<String> names = new ArrayList<>();
        Path dir = getSongsDir();
        if (!Files.exists(dir)) {
            log("Songs directory does not exist: " + dir);
            return names;
        }
        File[] files = dir.toFile().listFiles(f -> {
            String n = f.getName().toLowerCase();
            return f.isFile() && (n.endsWith(".mp3") || n.endsWith(".ogg"));
        });
        if (files == null) return names;
        for (File f : files) {
            String n = f.getName();
            int dot  = n.lastIndexOf('.');
            names.add(dot > 0 ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static Path getSongsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SONGS_FOLDER);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log("Created songs directory: " + dir);
            }
        } catch (Exception e) {
            log("ERROR: Could not create songs directory: " + e.getMessage());
        }
        return dir;
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private static void startPlayback(Path file, String ext, String songName, boolean loop) {
        currentSong = songName;
        looping     = loop;
        loopActive.set(true);
        playing     = true;

        playThread = new Thread(() -> {
            log("Playback thread started for: " + songName);
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    log("ERROR: MinecraftClient is null — cannot play audio");
                    return;
                }
                do {
                    log("Creating StabSoundInstance for: " + file);
                    StabSoundInstance inst = new StabSoundInstance(file, ext);
                    currentInstance = inst;

                    log("Submitting to SoundManager on main thread...");
                    client.execute(() -> {
                        try {
                            log("SoundManager.play() called");
                            client.getSoundManager().play(inst);
                            log("SoundManager.play() returned without exception");
                        } catch (Exception e) {
                            log("ERROR: SoundManager.play() threw: " + e.getClass().getName() + ": " + e.getMessage());
                        }
                    });

                    long durationMs = estimateDurationMs(file, ext);
                    log("Estimated duration: " + durationMs + "ms");
                    if (durationMs <= 0) durationMs = 300_000L;

                    long deadline = System.currentTimeMillis() + durationMs + 2000L;
                    while (System.currentTimeMillis() < deadline
                            && loopActive.get()
                            && !Thread.currentThread().isInterrupted()) {
                        Thread.sleep(200);
                    }
                    log("Playback wait finished. loopActive=" + loopActive.get() + " loop=" + loop);
                } while (loop && loopActive.get() && !Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                log("Playback thread interrupted (expected on stop)");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("ERROR in playback thread: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                playing = false;
                if (!loopActive.get()) currentSong = null;
                log("Playback thread finished");
            }
        }, "StabShot-PlayThread");
        playThread.setDaemon(true);
        playThread.start();
        log("Play thread started");
    }

    private static long estimateDurationMs(Path file, String ext) {
        try {
            if ("mp3".equals(ext)) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    Bitstream bs = new Bitstream(in);
                    Header h = bs.readFrame();
                    if (h == null) { log("WARN: Could not read MP3 header for duration estimate"); return 0; }
                    int bitrate = h.bitrate();
                    bs.closeFrame();
                    bs.close();
                    if (bitrate <= 0) return 0;
                    long size = Files.size(file);
                    long dur  = size * 8000L / bitrate;
                    log("MP3 duration estimate: " + dur + "ms (bitrate=" + bitrate + " size=" + size + ")");
                    return dur;
                }
            }
            return 240_000L;
        } catch (Exception e) {
            log("WARN: Could not estimate duration: " + e.getMessage());
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Sound instance
    // -----------------------------------------------------------------------

    @Environment(EnvType.CLIENT)
    static class StabSoundInstance extends AbstractSoundInstance {

        private final Path   file;
        private final String ext;

        StabSoundInstance(Path file, String ext) {
            super(DUMMY_EVENT, SoundCategory.MUSIC, Random.create());
            this.file            = file;
            this.ext             = ext;
            this.relative        = true;
            this.attenuationType = SoundInstance.AttenuationType.NONE;
            this.volume          = 1.0f;
            this.pitch           = 1.0f;
            this.repeat          = false;
            this.repeatDelay     = 0;
            this.x = 0; this.y = 0; this.z = 0;
            log("StabSoundInstance created: " + file.getFileName() + " category=MUSIC relative=true attenuation=NONE");
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(
                SoundLoader loader, Identifier id, boolean repeatInstantly) {
            log("getAudioStream() called for: " + file.getFileName());
            return CompletableFuture.supplyAsync(() -> {
                try {
                    log("Opening audio stream: " + file);
                    InputStream in = new BufferedInputStream(Files.newInputStream(file));
                    AudioStream stream = "mp3".equals(ext) ? new Mp3AudioStream(in) : new OggAudioStream(in);
                    log("Audio stream opened successfully: " + stream.getClass().getSimpleName()
                            + " format=" + stream.getFormat());
                    return stream;
                } catch (Exception e) {
                    log("ERROR: Failed to open audio stream for " + file + ": "
                            + e.getClass().getName() + ": " + e.getMessage());
                    throw new RuntimeException("Failed to open audio: " + file, e);
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // MP3 decoder
    // -----------------------------------------------------------------------

    @Environment(EnvType.CLIENT)
    static class Mp3AudioStream implements AudioStream {

        private final Bitstream bitstream;
        private final Decoder   decoder;
        private byte[]  overflow    = new byte[0];
        private int     overflowPos = 0;
        private int     sampleRate  = 44100;
        private int     channels    = 2;
        private boolean headerRead  = false;
        private int     framesDecoded = 0;

        Mp3AudioStream(InputStream in) {
            this.bitstream = new Bitstream(in);
            this.decoder   = new Decoder();
            log("Mp3AudioStream created");
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            while (overflow.length - overflowPos < size && decode()) {}
            int avail    = overflow.length - overflowPos;
            if (avail <= 0) {
                log("Mp3AudioStream: end of stream after " + framesDecoded + " frames");
                return ByteBuffer.allocateDirect(0);
            }
            int toReturn = Math.min(size, avail);
            ByteBuffer buf = ByteBuffer.allocateDirect(toReturn);
            buf.put(overflow, overflowPos, toReturn);
            overflowPos += toReturn;
            buf.flip();
            return buf;
        }

        private boolean decode() throws IOException {
            try {
                Header header = bitstream.readFrame();
                if (header == null) return false;
                if (!headerRead) {
                    sampleRate = header.frequency();
                    channels   = header.mode() == 3 ? 1 : 2;
                    headerRead = true;
                    log("Mp3AudioStream first frame: sampleRate=" + sampleRate + " channels=" + channels);
                }
                SampleBuffer out   = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[]      pcm   = out.getBuffer();
                int          count = out.getBufferLength();
                int          rem   = overflow.length - overflowPos;
                byte[]       next  = new byte[rem + count * 2];
                if (rem > 0) System.arraycopy(overflow, overflowPos, next, 0, rem);
                int off = rem;
                for (int i = 0; i < count; i++) {
                    next[off++] = (byte) (pcm[i] & 0xFF);
                    next[off++] = (byte) (pcm[i] >> 8 & 0xFF);
                }
                overflow    = next;
                overflowPos = 0;
                bitstream.closeFrame();
                framesDecoded++;
                return true;
            } catch (BitstreamException e) {
                if (e.getErrorCode() == BitstreamErrors.STREAM_EOF) {
                    log("Mp3AudioStream: EOF after " + framesDecoded + " frames");
                    return false;
                }
                log("ERROR: MP3 BitstreamException: " + e.getMessage());
                throw new IOException("MP3 bitstream error", e);
            } catch (DecoderException e) {
                log("ERROR: MP3 DecoderException: " + e.getMessage());
                throw new IOException("MP3 decoder error", e);
            }
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, 16, channels, channels * 2, sampleRate, false);
        }

        @Override
        public void close() throws IOException {
            log("Mp3AudioStream closed after " + framesDecoded + " frames");
            try { bitstream.close(); } catch (BitstreamException ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    static void log(String msg) {
        if (DEBUG) System.out.println(TAG + " " + msg);
    }
}
