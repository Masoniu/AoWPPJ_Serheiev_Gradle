package com.audioengine;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.util.fft.FFT;

import java.io.File;
import java.util.*;

/**
 * @author Serheiev Maksym
 */
public class FingerprintExtractor {
    private static final int FFT_SIZE = 4096;
    private static final int OVERLAP = 2048;
    private static final int NUM_PEAKS = 5;
    private static final int FAN_OUT = 3;
    private static final int TIME_DELTA = 10;
    private static final int MAX_FREQ = 512;

    public void processAudio(File audio, int songId, FingerprintStore store) {
        try {
            AudioDispatcher ad = AudioDispatcherFactory.fromPipe(
                    audio.getAbsolutePath(), 44100, FFT_SIZE, OVERLAP
            );
            ad.addAudioProcessor(new AudioProcessor() {
                FFT fft = new FFT(FFT_SIZE);
                float[] amplitudes = new float[FFT_SIZE / 2];
                List<int[]> peakBuffer = new ArrayList<>();
                int timeBlock = 0;

                @Override
                public boolean process(AudioEvent audioEvent) {
                    float[] buffer = audioEvent.getFloatBuffer().clone();
                    fft.forwardTransform(buffer);
                    fft.modulus(buffer, amplitudes);

                    for (long[] entry : extractHashes(amplitudes, timeBlock, peakBuffer)) {
                        store.addFingerprint(entry[0], songId, (int) entry[1]);
                    }
                    timeBlock++;
                    return true;
                }

                @Override
                public void processingFinished() {
                    System.out.println("Processing for " + store.getSongName(songId) + " finished");
                }
            });
            ad.run();
        } catch (Exception e) {
            System.err.println("Error processing file: " + audio.getName() + ": " + e.getMessage());
        }
    }

    private List<long[]> extractHashes(float[] amplitudes, int timeBlock, List<int[]> peakBuffer) {
        List<long[]> result = new ArrayList<>();
        int[] peaks = findTopPeaks(amplitudes);

        int prevSize = peakBuffer.size();

        for (int freq : peaks) {
            peakBuffer.add(new int[]{freq, timeBlock});
        }
        for (int t = prevSize; t < peakBuffer.size(); t++) {
            int[] target = peakBuffer.get(t);
            int startAnchor = Math.max(0, prevSize - FAN_OUT * NUM_PEAKS);
            for (int a = startAnchor; a < prevSize; a++) {
                int[] anchor = peakBuffer.get(a);
                int dt = target[1] - anchor[1];
                if (dt > 0 && dt <= TIME_DELTA) {
                    long hash = ((long) anchor[0] << 20) | ((long) target[0] << 8) | (dt & 0xFF);
                    result.add(new long[]{hash, anchor[1]});
                }
            }
        }
        int maxBufferSize = TIME_DELTA * NUM_PEAKS * 2;
        if (peakBuffer.size() > maxBufferSize) {
            peakBuffer.subList(0, peakBuffer.size() - maxBufferSize).clear();
        }

        return result;
    }

    private int[] findTopPeaks(float[] amplitudes) {
        int limit = Math.min(MAX_FREQ, amplitudes.length);
        int[] topFreqs = new int[NUM_PEAKS];
        float[] topAmps = new float[NUM_PEAKS];
        for (int freq = 10; freq < limit; freq++) {
            if (amplitudes[freq] > amplitudes[freq - 1]
                    && amplitudes[freq] > amplitudes[freq + 1]) {
                float amp = amplitudes[freq];
                int minIdx = 0;
                for (int k = 1; k < NUM_PEAKS; k++) {
                    if (topAmps[k] < topAmps[minIdx]) minIdx = k;
                }
                if (amp > topAmps[minIdx]) {
                    topAmps[minIdx] = amp;
                    topFreqs[minIdx] = freq;
                }
            }
        }
        return topFreqs;
    }
}