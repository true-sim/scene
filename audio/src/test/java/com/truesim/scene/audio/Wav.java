package com.truesim.scene.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Writes WAV files chunk by chunk for the reader's tests, odd ones included. */
final class Wav {

    private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();

    /** A {@code fmt } chunk; {@code extensible} wraps {@code format} in WAVE_FORMAT_EXTENSIBLE. */
    Wav fmt(int format, int channels, int rate, int bits, boolean extensible) {
        ByteBuffer b = le(extensible ? 40 : 16);
        b.putShort((short) (extensible ? 0xFFFE : format)).putShort((short) channels).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        if (extensible) {
            b.putShort((short) 22).putShort((short) bits).putInt(0).putShort((short) format)
                .put(new byte[] {0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71});
        }
        return chunk("fmt ", b.array());
    }

    /** A chunk of {@code id} holding {@code body}, padded to an even length. */
    Wav chunk(String id, byte[] body) {
        ByteBuffer header = le(8);
        header.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length);
        chunks.writeBytes(header.array());
        chunks.writeBytes(body);
        if (body.length % 2 == 1) {
            chunks.write(0);
        }
        return this;
    }

    /** A chunk header claiming {@code size} bytes, followed by {@code body} only. */
    Wav raw(String id, int size, byte[] body) {
        ByteBuffer header = le(8);
        header.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(size);
        chunks.writeBytes(header.array());
        chunks.writeBytes(body);
        return this;
    }

    /** 16-bit PCM samples as a data chunk. */
    Wav data16(short... samples) {
        ByteBuffer b = le(2 * samples.length);
        for (short s : samples) {
            b.putShort(s);
        }
        return chunk("data", b.array());
    }

    /** An {@code smpl} chunk with one loop from {@code start} to {@code end} inclusive, or none for -1. */
    Wav smpl(int start, int end) {
        ByteBuffer b = le(start < 0 ? 36 : 60);
        b.position(28);
        b.putInt(start < 0 ? 0 : 1).putInt(0);
        if (start >= 0) {
            b.putInt(0).putInt(0).putInt(start).putInt(end).putInt(0).putInt(0);
        }
        return chunk("smpl", b.array());
    }

    /** A {@code cue } chunk: pairs of (id, sample offset). */
    Wav cue(int... idAndFrame) {
        ByteBuffer b = le(4 + 12 * idAndFrame.length);
        b.putInt(idAndFrame.length / 2);
        for (int i = 0; i < idAndFrame.length; i += 2) {
            b.putInt(idAndFrame[i]).putInt(0).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0)
                .putInt(idAndFrame[i + 1]);
        }
        return chunk("cue ", b.array());
    }

    /** A {@code LIST adtl} chunk of {@code labl}s for (id, text) pairs. */
    Wav labels(Object... idAndText) {
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        list.writeBytes("adtl".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < idAndText.length; i += 2) {
            byte[] text = (idAndText[i + 1] + "\0").getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = le(12 + text.length + text.length % 2);
            b.put("labl".getBytes(StandardCharsets.US_ASCII)).putInt(4 + text.length).putInt((Integer) idAndText[i])
                .put(text);
            list.writeBytes(b.array());
        }
        return chunk("LIST", list.toByteArray());
    }

    /** The file: RIFF header and the chunks so far. */
    byte[] bytes() {
        byte[] body = chunks.toByteArray();
        ByteBuffer b = le(12 + body.length);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(4 + body.length)
            .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put(body);
        return b.array();
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
