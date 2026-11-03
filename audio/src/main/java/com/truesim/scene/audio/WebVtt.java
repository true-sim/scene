package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * WebVTT subtitle files (a line's {@code .vtt} sidecar, §2.15): the cues' times and text, each cue's text lines joined
 * by a newline. Headers, notes, styles, cue identifiers and settings after the times are skipped; times are
 * {@code hh:mm:ss.ttt} or {@code mm:ss.ttt}.
 */
@AllowAllocation("loading a line")
public final class WebVtt {

    private WebVtt() {
    }

    /** The cues of WebVTT text {@code vtt}. */
    public static List<Cue> parse(String vtt) {
        String[] lines = vtt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (!lines[0].replace("﻿", "").startsWith("WEBVTT")) { // a byte order mark may come first
            throw new IllegalArgumentException("Not a WebVTT file: it must start with WEBVTT");
        }
        List<Cue> cues = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int arrow = lines[i].indexOf("-->");
            if (arrow < 0) {
                continue;
            }
            double start = time(lines[i].substring(0, arrow).trim());
            String rest = lines[i].substring(arrow + 3).trim();
            int space = rest.indexOf(' ');
            double end = time(space < 0 ? rest : rest.substring(0, space));
            StringBuilder text = new StringBuilder();
            while (i + 1 < lines.length && !lines[i + 1].isBlank()) {
                text.append(text.isEmpty() ? "" : "\n").append(lines[++i]);
            }
            cues.add(new Cue(start, end, text.toString()));
        }
        return List.copyOf(cues);
    }

    private static double time(String t) {
        String[] parts = t.split(":");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Not a WebVTT time: " + t);
        }
        try {
            double seconds = Double.parseDouble(parts[parts.length - 1]);
            double minutes = Integer.parseInt(parts[parts.length - 2]);
            double hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
            return hours * 3600 + minutes * 60 + seconds;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a WebVTT time: " + t, e);
        }
    }
}
