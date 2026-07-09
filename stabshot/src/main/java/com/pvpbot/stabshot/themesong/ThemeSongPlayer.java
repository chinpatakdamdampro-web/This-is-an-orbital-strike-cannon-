package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.SampleBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.OggAudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    private static final Identifier DUMMY_ID = Identifier.of("stabshot", "dummy");
    private static final RegistryEntry<SoundEvent> DUMMY_SOUND_EVENT = SoundEvent.createUnregisteredEntry(DUMMY_ID);

    private static DiskSoundInstance currentInstance;
    private static String currentSong;
    private static boolean playing;
    private static boolean looping;
    private static Thread loopThread;
    private static final AtomicBoolean loopActive = new AtomicBoolean(false);

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
        for (final String e2 : new String[]{ "ogg", "mp3" }) {
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
        final MinecraftClient client = MinecraftClient.getInstance();
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
            (ThemeSongPlayer.loopThread = new Thread(() -> {
                while (ThemeSongPlayer.loopActive.get()) {
                    long durationMs = estimateDurationMs(fFile, fExt);
                    if (durationMs <= 0L) durationMs = 3000L;
                    final DiskSoundInstance inst2 = new DiskSoundInstance(fFile, fExt);
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            ThemeSongPlayer.currentInstance = inst2;
                        }
                        client.getSoundManager().play(inst2);
                    });
                    try {
                        Thread.sleep(durationMs);
                    } catch (final InterruptedException e3) {
                        break;
                    }
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            if (ThemeSongPlayer.currentInstance != null) {
                                client.getSoundManager().stop(ThemeSongPlayer.currentInstance);
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
                    client.getSoundManager().play(inst);
                } catch (final Exception e4) {
                    ThemeSongPlayer.playing = false;
                    if (client.inGameHud != null) {
                        client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal("§c[StabShot] Play error: " + e4.getMessage()));
                    }
                }
            });
        }
        return null;
    }

    /**
     * Used by SpotifyPlayer to play a cached preview MP3
     * through the same DiskSoundInstance / JLayer pipeline.
     */
    public static void playFile(final Path mp3File) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        final DiskSoundInstance inst = new DiskSoundInstance(mp3File, "mp3");
        client.execute(() -> {
            synchronized (ThemeSongPlayer.class) {
                if (ThemeSongPlayer.currentInstance != null) {
                    client.getSoundManager().stop(ThemeSongPlayer.currentInstance);
                }
                ThemeSongPlayer.currentInstance = inst;
            }
            client.getSoundManager().play(inst);
        });
    }

    private static long estimateDurationMs(final Path file, final String ext) {
        try {
            if (ext.equals("mp3")) {
                try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    final Bitstream bs = new Bitstream(in);
                    final Header first = bs.readFrame();
                    if (first == null) { bs.close(); return 0L; }
                    final int bitrate = first.bitrate();
                    bs.closeFrame();
                    bs.close();
                    if (bitrate <= 0) return 0L;
                    final long fileSize = Files.size(file);
                    return fileSize * 8000L / bitrate;
                }
            }
            try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                final OggAudioStream ogg = new OggAudioStream(in);
                final AudioFormat fmt = ogg.getFormat();
                final int sr = (int) fmt.getSampleRate();
                final int ch = fmt.getChannels();
                long totalBytes = 0L;
                ByteBuffer buf;
                while ((buf = ogg.getBuffer(8192)) != null && buf.remaining() > 0) {
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
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                final DiskSoundInstance inst = ThemeSongPlayer.currentInstance;
                client.execute(() -> client.getSoundManager().stop(inst));
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
        if (!Files.exists(dir, new LinkOption[0])) return names;
        final File[] files = dir.toFile().listFiles(f -> {
            if (!f.isFile()) return false;
            final String n2 = f.getName().toLowerCase();
            return n2.endsWith(".ogg") || n2.endsWith(".mp3");
        });
        if (files == null) return names;
        for (final File f : files) {
            final String n = f.getName();
            final int dot = n.lastIndexOf('.');
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

    @Environment(EnvType.CLIENT)
    static class DiskSoundInstance extends AbstractSoundInstance implements FabricSoundInstance
    {
        private final Path filePath;
        private final String ext;

        DiskSoundInstance(final Path filePath, final String ext) {
            super(DUMMY_SOUND_EVENT, SoundCategory.MUSIC, Random.create());
            this.filePath = filePath;
            this.ext = ext;
            this.volume = 1.0f;
            this.pitch  = 1.0f;
            this.repeat = false;
            this.repeatDelay = 0;
            this.relative = true;
            this.attenuationType = net.minecraft.client.sound.SoundInstance.AttenuationType.NONE;
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            try {
                final InputStream in = new BufferedInputStream(Files.newInputStream(this.filePath));
                final AudioStream stream = this.ext.equals("mp3") ? new Mp3AudioStream(in) : new OggAudioStream(in);
                return CompletableFuture.completedFuture(stream);
            } catch (final IOException e) {
                return CompletableFuture.failedFuture(new RuntimeException("StabShot: can't open audio: " + this.filePath + " — " + e.getMessage(), e));
            }
        }
    }

    @Environment(EnvType.CLIENT)
    static class Mp3AudioStream implements AudioStream
    {
        private final Bitstream bitstream;
        private final Decoder decoder;
        private byte[] overflowBytes = new byte[0];
        private int overflowPos = 0;
        private int sampleRate = 44100;
        private int channels = 2;
        private boolean headerRead = false;

        Mp3AudioStream(final InputStream in) {
            this.bitstream = new Bitstream(in);
            this.decoder   = new Decoder();
        }

        @Override
        public ByteBuffer getBuffer(final int size) throws IOException {
            while (this.overflowBytes.length - this.overflowPos < size && this.decodeNextFrame()) {}
            final int available = this.overflowBytes.length - this.overflowPos;
            if (available <= 0) return ByteBuffer.allocateDirect(0);
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
                    this.channels   = (header.mode() == 3) ? 1 : 2;
                    this.headerRead = true;
                }
                final SampleBuffer output = (SampleBuffer) this.decoder.decodeFrame(header, this.bitstream);
                this.bitstream.closeFrame();
                final short[] samples = output.getBuffer();
                final int count = output.getBufferLength();
                final int remaining = this.overflowBytes.length - this.overflowPos;
                final byte[] newBuf = new byte[remaining + count * 2];
                if (remaining > 0) System.arraycopy(this.overflowBytes, this.overflowPos, newBuf, 0, remaining);
                int off = remaining;
                for (final short s : samples) {
                    newBuf[off++] = (byte)(s & 0xFF);
                    newBuf[off++] = (byte)(s >> 8 & 0xFF);
                }
                this.overflowBytes = newBuf;
                this.overflowPos   = 0;
                return true;
            } catch (final BitstreamException e) {
                if (e.getErrorCode() == 260) return false;
                throw new IOException("MP3 bitstream error: " + e.getMessage(), e);
            } catch (final DecoderException e2) {
                throw new IOException("MP3 decoder error: " + e2.getMessage(), e2);
            }
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    (float) this.sampleRate, 16, this.channels,
                    this.channels * 2, (float) this.sampleRate, false);
        }

        @Override
        public void close() throws IOException {
            try { this.bitstream.close(); } catch (final BitstreamException ignored) {}
        }
    }
}
