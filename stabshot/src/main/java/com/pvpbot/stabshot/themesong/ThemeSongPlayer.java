package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.class_4234;
import net.minecraft.class_4237;
import net.minecraft.class_5819;
import net.minecraft.class_4228;
import net.minecraft.class_3419;
import net.minecraft.class_1102;
import net.minecraft.class_1113;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_3414;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.fabricmc.loader.api.FabricLoader;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public class ThemeSongPlayer
{
    public static final String SONGS_FOLDER = "stabshot/songs";
    private static final class_2960 DUMMY_ID;
    private static final class_3414 DUMMY_SOUND_EVENT;
    private static DiskSoundInstance currentInstance;
    private static String currentSong;
    private static boolean playing;
    private static boolean looping;
    private static Thread loopThread;
    private static AtomicBoolean loopActive;

    public static String play(final String name, final boolean loop) {
        stop();
        final Path songsDir = getSongsDir();
        if (!Files.exists(songsDir, new LinkOption[0])) {
            try {
                Files.createDirectories(songsDir, (FileAttribute<?>[]) new FileAttribute[0]);
            } catch (final Exception e) {
                return "Could not create songs folder: " + e.getMessage();
            }
        }
        Path file = null;
        String ext = null;
        final String[] array = { "ogg", "mp3" };
        for (int length = array.length, i = 0; i < length; ++i) {
            final String e2 = array[i];
            final Path candidate = songsDir.resolve(name + "." + e2);
            if (Files.exists(candidate, new LinkOption[0])) {
                file = candidate;
                ext = e2;
                break;
            }
        }
        if (file == null) {
            return "§cSong not found: §f" + name + ".ogg §7or §f" + name + ".mp3\n§7Put audio files in: §f" + songsDir + "\n§7Available: §f" + String.join(", ", getSongNames());
        }
        final class_310 client = class_310.method_1551();
        if (client == null) {
            return "Client not ready.";
        }
        final Path fFile = file;
        final String fExt = ext;
        ThemeSongPlayer.currentSong = name;
        ThemeSongPlayer.playing = true;
        ThemeSongPlayer.looping = loop;
        if (loop) {
            ThemeSongPlayer.loopActive.set(true);
            final DiskSoundInstance inst;
            (ThemeSongPlayer.loopThread = new Thread(() -> {
                while (ThemeSongPlayer.loopActive.get()) {
                    long durationMs = estimateDurationMs(fFile, fExt);
                    if (durationMs <= 0L) {
                        durationMs = 3000L;
                    }
                    final DiskSoundInstance inst2 = new DiskSoundInstance(fFile, fExt);
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            ThemeSongPlayer.currentInstance = inst2;
                        }
                        client.method_1483().method_4873((class_1113) inst2);
                    });
                    try {
                        Thread.sleep(durationMs);
                    } catch (final InterruptedException e3) {
                        break;
                    }
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            if (ThemeSongPlayer.currentInstance != null) {
                                client.method_1483().method_4870((class_1113) ThemeSongPlayer.currentInstance);
                            }
                        }
                    });
                }
            }, "StabShot-LoopThread")).setDaemon(true);
            ThemeSongPlayer.loopThread.start();
        } else {
            final DiskSoundInstance inst = new DiskSoundInstance(fFile, fExt);
            client.execute(() -> {
                try {
                    synchronized (ThemeSongPlayer.class) {
                        ThemeSongPlayer.currentInstance = inst;
                    }
                    client.method_1483().method_4873((class_1113) inst);
                } catch (final Exception e4) {
                    ThemeSongPlayer.playing = false;
                    if (client.field_1724 != null) {
                        client.field_1724.method_7353((class_2561) class_2561.method_43470("§c[StabShot] Play error: " + e4.getMessage()), false);
                    }
                }
            });
        }
        return null;
    }

    /**
     * NEW — used by SpotifyPlayer to play a cached Spotify preview MP3
     * through the same DiskSoundInstance / JLayer pipeline.
     */
    public static void playFile(final Path mp3File) {
        final class_310 client = class_310.method_1551();
        if (client == null) return;
        final DiskSoundInstance inst = new DiskSoundInstance(mp3File, "mp3");
        client.execute(() -> {
            synchronized (ThemeSongPlayer.class) {
                if (ThemeSongPlayer.currentInstance != null) {
                    client.method_1483().method_4870((class_1113) ThemeSongPlayer.currentInstance);
                }
                ThemeSongPlayer.currentInstance = inst;
            }
            client.method_1483().method_4873((class_1113) inst);
        });
    }

    private static long estimateDurationMs(final Path file, final String ext) {
        try {
            if (ext.equals("mp3")) {
                try (final InputStream in = new BufferedInputStream(Files.newInputStream(file, new OpenOption[0]))) {
                    final Bitstream bs = new Bitstream(in);
                    final Header first = bs.readFrame();
                    if (first == null) {
                        bs.close();
                        return 0L;
                    }
                    final int bitrate = first.bitrate();
                    bs.closeFrame();
                    bs.close();
                    if (bitrate <= 0) return 0L;
                    final long fileSize = Files.size(file);
                    return fileSize * 8000L / bitrate;
                }
            }
            try (final InputStream in = new BufferedInputStream(Files.newInputStream(file, new OpenOption[0]))) {
                final class_4228 ogg = new class_4228(in);
                final AudioFormat fmt = ogg.method_19719();
                final int sr = (int) fmt.getSampleRate();
                final int ch = fmt.getChannels();
                long totalBytes = 0L;
                ByteBuffer buf;
                while ((buf = ogg.method_19720(8192)) != null && buf.remaining() > 0) {
                    totalBytes += buf.remaining();
                }
                ogg.close();
                if (sr <= 0 || ch <= 0) return 0L;
                return totalBytes * 1000L / (sr * ch * 2L);
            }
        } catch (final Exception e) {
            return 0L;
        }
    }

    public static void stop() {
        ThemeSongPlayer.loopActive.set(false);
        if (ThemeSongPlayer.loopThread != null) {
            ThemeSongPlayer.loopThread.interrupt();
            ThemeSongPlayer.loopThread = null;
        }
        if (ThemeSongPlayer.currentInstance != null) {
            final class_310 client = class_310.method_1551();
            if (client != null) {
                final DiskSoundInstance inst = ThemeSongPlayer.currentInstance;
                client.execute(() -> client.method_1483().method_4870((class_1113) inst));
            }
            ThemeSongPlayer.currentInstance = null;
        }
        ThemeSongPlayer.currentSong = null;
        ThemeSongPlayer.playing = false;
        ThemeSongPlayer.looping = false;
    }

    public static List<String> getSongNames() {
        final List<String> names = new ArrayList<>();
        final Path dir = getSongsDir();
        if (!Files.exists(dir, new LinkOption[0])) {
            return names;
        }
        final File[] files = dir.toFile().listFiles(f -> {
            if (!f.isFile()) return false;
            final String n2 = f.getName().toLowerCase();
            return n2.endsWith(".ogg") || n2.endsWith(".mp3");
        });
        if (files == null) return names;
        for (final File f : files) {
            final String n = f.getName();
            final int dot = n.lastIndexOf(46);
            names.add((dot > 0) ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static boolean isPlaying()      { return ThemeSongPlayer.playing; }
    public static boolean isLooping()      { return ThemeSongPlayer.looping; }
    public static String  getCurrentSong() { return ThemeSongPlayer.currentSong; }

    public static Path getSongsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("stabshot/songs");
    }

    static {
        DUMMY_ID = class_2960.method_60655("stabshot", "dummy");
        DUMMY_SOUND_EVENT = class_3414.method_47908(ThemeSongPlayer.DUMMY_ID);
        ThemeSongPlayer.currentInstance = null;
        ThemeSongPlayer.currentSong = null;
        ThemeSongPlayer.playing = false;
        ThemeSongPlayer.looping = false;
        ThemeSongPlayer.loopThread = null;
        ThemeSongPlayer.loopActive = new AtomicBoolean(false);
    }

    @Environment(EnvType.CLIENT)
    static class DiskSoundInstance extends class_1102 implements FabricSoundInstance
    {
        private final Path filePath;
        private final String ext;

        DiskSoundInstance(final Path filePath, final String ext) {
            super(ThemeSongPlayer.DUMMY_SOUND_EVENT, class_3419.field_15250, class_5819.method_43047());
            this.filePath = filePath;
            this.ext = ext;
            this.field_5442 = 1.0f;
            this.field_5441 = 1.0f;
            this.field_5446 = false;
            this.field_5451 = 0;
            this.field_18936 = true;
            this.field_5440 = class_1113.class_1114.field_5478;
        }

        public CompletableFuture<class_4234> getAudioStream(final class_4237 loader, final class_2960 id, final boolean repeatInstantly) {
            try {
                final InputStream in = new BufferedInputStream(Files.newInputStream(this.filePath, new OpenOption[0]));
                final class_4234 stream = (class_4234) (this.ext.equals("mp3") ? new Mp3AudioStream(in) : new class_4228(in));
                return CompletableFuture.completedFuture(stream);
            } catch (final IOException e) {
                return CompletableFuture.failedFuture(new RuntimeException("StabShot: can't open audio: " + this.filePath + " — " + e.getMessage(), e));
            }
        }
    }

    @Environment(EnvType.CLIENT)
    static class Mp3AudioStream implements class_4234
    {
        private final Bitstream bitstream;
        private final Decoder decoder;
        private byte[] overflowBytes;
        private int overflowPos;
        private int sampleRate;
        private int channels;
        private boolean headerRead;

        Mp3AudioStream(final InputStream in) {
            this.overflowBytes = new byte[0];
            this.overflowPos = 0;
            this.sampleRate = 44100;
            this.channels = 2;
            this.headerRead = false;
            this.bitstream = new Bitstream(in);
            this.decoder = new Decoder();
        }

        public ByteBuffer method_19720(final int size) throws IOException {
            while (this.overflowBytes.length - this.overflowPos < size && this.decodeNextFrame()) {}
            final int available = this.overflowBytes.length - this.overflowPos;
            if (available <= 0) {
                return ByteBuffer.allocateDirect(0);
            }
            final int toReturn = Math.min(size, available);
            final ByteBuffer buf = ByteBuffer.allocateDirect(toReturn);
            buf.put(this.overflowBytes, this.overflowPos, toReturn);
            this.overflowPos += toReturn;
            buf.flip();
            return buf;
        }

        private boolean decodeNextFrame() throws IOException {
            try {
                final Header header = this.bitstream.readFrame();
                if (header == null) return false;
                if (!this.headerRead) {
                    this.sampleRate = header.frequency();
                    this.channels = (header.mode() == 3) ? 1 : 2;
                    this.headerRead = true;
                }
                final SampleBuffer output = (SampleBuffer) this.decoder.decodeFrame(header, this.bitstream);
                this.bitstream.closeFrame();
                final short[] samples = output.getBuffer();
                final int count = output.getBufferLength();
                final int remaining = this.overflowBytes.length - this.overflowPos;
                final byte[] newBuf = new byte[remaining + count * 2];
                if (remaining > 0) {
                    System.arraycopy(this.overflowBytes, this.overflowPos, newBuf, 0, remaining);
                }
                int off = remaining;
                for (final short s : samples) {
                    newBuf[off++] = (byte) (s & 0xFF);
                    newBuf[off++] = (byte) (s >> 8 & 0xFF);
                }
                this.overflowBytes = newBuf;
                this.overflowPos = 0;
                return true;
            } catch (final BitstreamException e) {
                if (e.getErrorCode() == 260) return false;
                throw new IOException("MP3 bitstream error: " + e.getMessage(), e);
            } catch (final DecoderException e2) {
                throw new IOException("MP3 decoder error: " + e2.getMessage(), e2);
            }
        }

        public AudioFormat method_19719() {
            final int frameSize = this.channels * 2;
            return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, (float) this.sampleRate, 16, this.channels, frameSize, (float) this.sampleRate, false);
        }

        public void close() throws IOException {
            try {
                this.bitstream.close();
            } catch (final BitstreamException ex) {}
        }
    }
}
