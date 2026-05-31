import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.util.fft.FFT;

import java.util.*;

/**
 * @author Serheiev Maksym
 */
public class HybridEngine {
    private static final int NUM_PEAKS = 5;
    private static final int FAN_OUT = 3;
    private static final int TIME_DELTA = 10;
    private static final int MAX_FREQ = 512;
    private final AudioMatcher audioMatcher;
    private final MelodyMatcher melodyMatcher;

    private static final int FFT_SIZE = 4096;
    private static final int OVERLAP = 2048;
    private static final int[] FREQUENCIES = {40, 80, 120, 180, 300};

    public HybridEngine(AudioMatcher audioMatcher, MelodyMatcher melodyMatcher) {
        this.audioMatcher = audioMatcher;
        this.melodyMatcher = melodyMatcher;
    }

    public void listenAndSearch(int listenTime) {
        System.out.println("Recording is on for " + listenTime + " seconds");
        try {
            javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(44100, 16, 1, true, false);
            javax.sound.sampled.DataLine.Info dataLineInfo = new javax.sound.sampled.DataLine.Info(
                    javax.sound.sampled.TargetDataLine.class, format
            );

            javax.sound.sampled.TargetDataLine line = null;
            javax.sound.sampled.Mixer.Info[] mixers = javax.sound.sampled.AudioSystem.getMixerInfo();
            for (javax.sound.sampled.Mixer.Info mixerInfo : mixers) {
                if (mixerInfo.getName().contains("Microphone") && !mixerInfo.getName().contains("Primary")) {
                    javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(dataLineInfo)) {
                        try {
                            line = (javax.sound.sampled.TargetDataLine) mixer.getLine(dataLineInfo);
                            System.out.println("Using microphone: " + mixerInfo.getName());
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (line == null) {
                for (javax.sound.sampled.Mixer.Info mixerInfo : mixers) {
                    if (mixerInfo.getName().contains("Capture") && !mixerInfo.getName().contains("Primary")) {
                        javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(mixerInfo);
                        if (mixer.isLineSupported(dataLineInfo)) {
                            try {
                                line = (javax.sound.sampled.TargetDataLine) mixer.getLine(dataLineInfo);
                                System.out.println("Using microphone: " + mixerInfo.getName());
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (line == null) {
                System.err.println("Microphone not found, falling back to default");
                line = (javax.sound.sampled.TargetDataLine) javax.sound.sampled.AudioSystem.getLine(dataLineInfo);
            }

            line.open(format);
            line.start();

            javax.sound.sampled.AudioInputStream audioStream = new javax.sound.sampled.AudioInputStream(line);
            be.tarsos.dsp.io.jvm.JVMAudioInputStream tarsosStream = new be.tarsos.dsp.io.jvm.JVMAudioInputStream(audioStream);
            AudioDispatcher ad = new AudioDispatcher(tarsosStream, FFT_SIZE, OVERLAP);

            List<QueryPoint> queryHashes = new ArrayList<>();
            List<Integer> queryPitches = new ArrayList<>();
            List<int[]> peakBuffer = new ArrayList<>(); // ← винесено НАЗОВНІ анонімного класу

            ad.addAudioProcessor(new AudioProcessor() {
                FFT fft = new FFT(FFT_SIZE);
                float[] amplitudes = new float[FFT_SIZE / 2];
                int offset = 0;

                @Override
                public boolean process(AudioEvent audioEvent) {
                    float[] buffer = audioEvent.getFloatBuffer().clone();
                    fft.forwardTransform(buffer);
                    fft.modulus(buffer, amplitudes);
                    for (long[] entry : extractHashes(amplitudes, offset, peakBuffer)) {
                        queryHashes.add(new QueryPoint(entry[0], (int) entry[1]));
                    }
                    offset++;
                    return true;
                }

                @Override
                public void processingFinished() {}
            });

            ad.addAudioProcessor(new PitchProcessor(
                    PitchProcessor.PitchEstimationAlgorithm.YIN, 44100, FFT_SIZE,
                    (result, event) -> {
                        float pitchInHz = result.getPitch();
                        if (pitchInHz > 0) {
                            int midiNote = (int) Math.round(69 + 12 * (Math.log(pitchInHz / 440.0) / Math.log(2)));
                            if (queryPitches.isEmpty() || queryPitches.get(queryPitches.size() - 1) != midiNote) {
                                queryPitches.add(midiNote);
                            }
                        }
                    }
            ));

            new Thread(() -> {
                try {
                    Thread.sleep(listenTime * 1000L);
                    ad.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();

            ad.run();
            line.stop();
            line.close();

            try { Thread.sleep(200); } catch (InterruptedException e) { e.printStackTrace(); }

            System.out.println("Finished recording, processing...");
            System.out.println("Collected hashes: " + queryHashes.size() + ", pitches: " + queryPitches.size());
            performCascadeSearch(queryHashes, queryPitches);

        } catch (javax.sound.sampled.LineUnavailableException e) {
            System.err.println("Error: Microphone is unavailable or used by another program");
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
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

    public SearchResult performCascadeSearch(List<QueryPoint> hashes, List<Integer> pitches){
        System.out.println("\nSearching by audio fingerprint");
        SearchResult audioResult = audioMatcher.findMatch(hashes);
        System.out.println("Audio score: " + audioResult.score + " | Song: " + audioResult.songName);
        if(audioResult.score >= 200){
            System.out.println("Found the exact match: "+audioResult.songName+" (Score: "+audioResult.score+")");
            return audioResult;
        }
        System.out.println("The exact match was not found, starting the pitch search");
        String contour = convertToParson(pitches);
        System.out.println("Read contour: "+contour);
        SearchResult melodyResult = melodyMatcher.findMatch(contour);
        if(melodyResult.score >= 100){
            System.out.println("Found a match: "+melodyResult.songName+" (Matched k-grams: "+melodyResult.score+")");
            return melodyResult;
        }else{
            System.out.println("No matches found");
            return new SearchResult("Track not found", 0);
        }
    }

    private String convertToParson(List<Integer> pitches){
        if(pitches.size()<2) return "";
        StringBuilder sb = new StringBuilder();
        for(int i=1; i<pitches.size(); i++){
            int prev = pitches.get(i-1);
            int cur = pitches.get(i);
            if(cur>prev) sb.append("U");
            else if(cur<prev) sb.append("D");
            else sb.append("S");
        }
        return sb.toString();
    }

    public SearchResult searchFromFile(java.io.File audioFile) {
        System.out.println("Processing audio from WEB browser...");
        try {
            AudioDispatcher ad = AudioDispatcherFactory.fromPipe(
                    audioFile.getAbsolutePath(), 44100, FFT_SIZE, OVERLAP
            );

            List<QueryPoint> queryHashes = new ArrayList<>();
            List<Integer> queryPitches = new ArrayList<>();
            List<int[]> peakBuffer = new ArrayList<>();

            ad.addAudioProcessor(new AudioProcessor() {
                FFT fft = new FFT(FFT_SIZE);
                float[] amplitudes = new float[FFT_SIZE / 2];
                int offset = 0;

                @Override
                public boolean process(AudioEvent audioEvent) {
                    float[] buffer = audioEvent.getFloatBuffer().clone();
                    fft.forwardTransform(buffer);
                    fft.modulus(buffer, amplitudes);
                    for (long[] entry : extractHashes(amplitudes, offset, peakBuffer)) {
                        queryHashes.add(new QueryPoint(entry[0], (int) entry[1]));
                    }
                    offset++;
                    return true;
                }
                @Override public void processingFinished() {}
            });

            ad.addAudioProcessor(new PitchProcessor(
                    PitchProcessor.PitchEstimationAlgorithm.YIN, 44100, FFT_SIZE,
                    (result, event) -> {
                        float pitchInHz = result.getPitch();
                        if (pitchInHz > 0) {
                            int midiNote = (int) Math.round(69 + 12 * (Math.log(pitchInHz / 440.0) / Math.log(2)));
                            if (queryPitches.isEmpty() || queryPitches.get(queryPitches.size() - 1) != midiNote) {
                                queryPitches.add(midiNote);
                            }
                        }
                    }
            ));
            ad.run();
            System.out.println("Collected hashes: " + queryHashes.size() + ", pitches: " + queryPitches.size());
            return performCascadeSearch(queryHashes, queryPitches);

        } catch (Exception e) {
            e.printStackTrace();
            return new SearchResult("Error processing file", 0);
        }
    }
}
