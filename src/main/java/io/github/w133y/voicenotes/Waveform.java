package io.github.w133y.voicenotes;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Measures loudness while recording and reduces it to a few bars for display. */
final class Waveform {

    private Waveform() {
    }

    /**
     * Loudness of one Opus frame. Uses the RMS of the decoded audio; without a decoder,
     * falls back to the packet size, which roughly follows loudness with variable bitrate.
     */
    static float level(@Nullable OpusDecoder decoder, byte[] opusFrame) {
        if (decoder == null) return opusFrame.length;
        try {
            short[] pcm = decoder.decode(opusFrame);
            if (pcm.length == 0) return 0;
            double sum = 0;
            for (short sample : pcm) sum += (double) sample * sample;
            return (float) Math.sqrt(sum / pcm.length);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Groups frame levels into bars, each in range 0..1. Characters and colors are applied
     * later by NoteFormat, so changing them in the config affects existing notes too.
     */
    static float[] compute(List<Float> levels, int bars) {
        if (levels.isEmpty()) return new float[0];

        int count = Math.min(bars, levels.size());
        double[] buckets = new double[count];
        int[] sizes = new int[count];
        for (int i = 0; i < levels.size(); i++) {
            int bucket = (int) ((long) i * count / levels.size());
            buckets[bucket] += levels.get(i);
            sizes[bucket]++;
        }

        double max = 0;
        for (int i = 0; i < count; i++) {
            buckets[i] /= Math.max(1, sizes[i]);
            max = Math.max(max, buckets[i]);
        }

        float[] result = new float[count];
        for (int i = 0; i < count; i++) {
            // Relative to the loudest bar, so quiet recordings still show their shape;
            // sqrt lifts quieter parts so the line doesn't look mostly flat
            double normalized = max <= 0 ? 0 : buckets[i] / max;
            result[i] = (float) Math.min(1.0, Math.sqrt(normalized));
        }
        return result;
    }
}
