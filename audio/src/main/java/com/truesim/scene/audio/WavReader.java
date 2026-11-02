package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads WAV files (RIFF {@code WAVE}) into clips: PCM of 8, 16, 24 and 32 bits, 32 and 64-bit floats, plain or
 * {@code WAVE_FORMAT_EXTENSIBLE}, any rate, 1 to 8 channels. 8 and 16-bit samples stay 16-bit; the others become
 * floats. The first {@code smpl} loop sets the loop region; {@code cue} points become markers, named by their
 * {@code LIST adtl labl}. Unknown chunks are skipped, odd-sized ones with their pad byte.
 */
@AllowAllocation("loading a clip")
public final class WavReader {

    private static final int PCM = 1;
    private static final int IEEE_FLOAT = 3;
    private static final int EXTENSIBLE = 0xFFFE;

    private WavReader() {
    }

    /** Reads the WAV file {@code in} holds; does not close it. */
    public static AudioClip read(InputStream in) throws IOException {
        return read(in.readAllBytes());
    }

    /** Reads the WAV file in {@code bytes}. */
    public static AudioClip read(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || !tag(b, 0).equals("RIFF") || !tag(b, 8).equals("WAVE")) {
            throw new IOException("Not a WAV file (no RIFF WAVE header)");
        }
        int format = -1;
        int channels = 0;
        int rate = 0;
        int blockAlign = 0;
        int bits = 0;
        int dataStart = -1;
        int dataLength = 0;
        long loopStart = -1;
        long loopEnd = -1;
        Map<Integer, Long> cues = new HashMap<>();
        Map<Integer, String> labels = new HashMap<>();
        int at = 12;
        while (at + 8 <= bytes.length) {
            String id = tag(b, at);
            long size = b.getInt(at + 4) & 0xFFFFFFFFL;
            int body = at + 8;
            if (body + size > bytes.length) {
                if (!id.equals("data")) {
                    throw new IOException("The WAV file is cut short in its " + id + " chunk");
                }
                size = bytes.length - body; // a writer that never patched the data size: take what is there
            }
            switch (id) {
                case "fmt " -> {
                    if (size < 16) {
                        throw new IOException("A fmt chunk of " + size + " bytes");
                    }
                    format = b.getShort(body) & 0xFFFF;
                    channels = b.getShort(body + 2) & 0xFFFF;
                    rate = b.getInt(body + 4);
                    blockAlign = b.getShort(body + 12) & 0xFFFF;
                    bits = b.getShort(body + 14) & 0xFFFF;
                    if (format == EXTENSIBLE) {
                        if (size < 40) {
                            throw new IOException("An extensible fmt chunk of " + size + " bytes");
                        }
                        format = b.getShort(body + 24) & 0xFFFF; // the sub-format GUID's first two bytes
                    }
                }
                case "data" -> {
                    dataStart = body;
                    dataLength = (int) size;
                }
                case "smpl" -> {
                    if (size >= 60 && b.getInt(body + 28) > 0) {
                        loopStart = b.getInt(body + 36 + 8) & 0xFFFFFFFFL;
                        loopEnd = (b.getInt(body + 36 + 12) & 0xFFFFFFFFL) + 1; // stored inclusive
                    }
                }
                case "cue " -> {
                    int count = (int) Math.min(b.getInt(body), (size - 4) / 24);
                    for (int i = 0; i < count; i++) {
                        int point = body + 4 + 24 * i;
                        cues.put(b.getInt(point), b.getInt(point + 20) & 0xFFFFFFFFL);
                    }
                }
                case "LIST" -> {
                    if (size >= 4 && tag(b, body).equals("adtl")) {
                        labels(b, body + 4, body + (int) size, labels);
                    }
                }
                default -> {
                    // not ours: skipped
                }
            }
            at = body + (int) size + (int) (size & 1);
        }
        if (format < 0) {
            throw new IOException("The WAV file has no fmt chunk");
        }
        if (dataStart < 0) {
            throw new IOException("The WAV file has no data chunk");
        }
        if (channels < 1 || channels > 8) {
            throw new IOException("WAV files of 1 to 8 channels are read, not " + channels);
        }
        if (rate <= 0) {
            throw new IOException("Not a sample rate: " + rate);
        }
        boolean floating = format == IEEE_FLOAT;
        if (!floating && format != PCM) {
            throw new IOException("WAV format " + format + " is not PCM or float");
        }
        if (floating ? bits != 32 && bits != 64 : bits != 8 && bits != 16 && bits != 24 && bits != 32) {
            throw new IOException((floating ? "Float" : "PCM") + " samples of " + bits + " bits are not read");
        }
        if (blockAlign != channels * bits / 8) {
            throw new IOException("A block of " + blockAlign + " bytes for " + channels + " channels of " + bits
                + " bits");
        }
        int frames = dataLength / blockAlign;
        int count = frames * channels;
        AudioClip.Builder clip = AudioClip.builder(rate, channels);
        if (!floating && bits <= 16) {
            short[] s = new short[count];
            for (int i = 0; i < count; i++) {
                s[i] = bits == 8 ? (short) (((bytes[dataStart + i] & 0xFF) - 128) << 8)
                    : b.getShort(dataStart + 2 * i);
            }
            clip.pcm16(s);
        } else {
            float[] f = new float[count];
            int width = bits / 8;
            for (int i = 0; i < count; i++) {
                int p = dataStart + width * i;
                f[i] = switch (bits) {
                    case 24 -> ((bytes[p] & 0xFF) | (bytes[p + 1] & 0xFF) << 8 | bytes[p + 2] << 16) * 0x1p-23f;
                    case 64 -> (float) b.getDouble(p);
                    default -> floating ? b.getFloat(p) : b.getInt(p) * 0x1p-31f;
                };
            }
            clip.floats(f);
        }
        if (loopEnd > loopStart && loopEnd <= frames) {
            clip.loop(loopStart, loopEnd);
        }
        for (Map.Entry<Integer, Long> cue : cues.entrySet()) {
            if (cue.getValue() <= frames) {
                clip.marker(labels.getOrDefault(cue.getKey(), "cue" + cue.getKey()), cue.getValue());
            }
        }
        return clip.build();
    }

    /** Reads the {@code labl} sub-chunks of an {@code adtl} list between {@code at} and {@code end}. */
    private static void labels(ByteBuffer b, int at, int end, Map<Integer, String> labels) {
        while (at + 8 <= end) {
            int size = b.getInt(at + 4);
            if (size < 0 || at + 8 + size > end) {
                return;
            }
            if (tag(b, at).equals("labl") && size >= 4) {
                int text = at + 12;
                int stop = text;
                while (stop < at + 8 + size && b.get(stop) != 0) {
                    stop++;
                }
                labels.put(b.getInt(at + 8), new String(b.array(), text, stop - text, StandardCharsets.UTF_8));
            }
            at += 8 + size + (size & 1);
        }
    }

    private static String tag(ByteBuffer b, int at) {
        return new String(b.array(), at, 4, StandardCharsets.US_ASCII);
    }
}
