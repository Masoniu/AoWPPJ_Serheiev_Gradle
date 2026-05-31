import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.File;

/**
 * @author Serheiev Maksym
 */
public class Main {
    private static final String AUDIO_INDEX_FILE  = "audio_index.dat";
    private static final String MELODY_INDEX_FILE = "melody_index.dat";
    private static final String AUDIO_DIR = "src/main/resources/audio_dataset";
    private static final String MIDI_DIR  = "src/main/resources/clean_midi";

    public static void main(String[] args) {
        Logger.getLogger("be.tarsos").setLevel(Level.SEVERE);

        InvertedIndex audioIndex;
        MelodyIndex melodyIndex;

        File audioIndexFile  = new File(AUDIO_INDEX_FILE);
        File melodyIndexFile = new File(MELODY_INDEX_FILE);
        if (audioIndexFile.exists() && melodyIndexFile.exists()) {
            System.out.println("Found indexes in cache:");
            audioIndex  = (InvertedIndex)IndexStorage.load(AUDIO_INDEX_FILE);
            melodyIndex = (MelodyIndex)IndexStorage.load(MELODY_INDEX_FILE);
        } else {
            System.out.println("Could not find indexes in cache, starting indexing");
            melodyIndex = new MelodyIndex();
            audioIndex = buildAudioIndexWithSPIMI();
            buildMelodyIndex(melodyIndex);
            melodyIndex.freeze();
            IndexStorage.save(audioIndex,  AUDIO_INDEX_FILE);
            IndexStorage.save(melodyIndex, MELODY_INDEX_FILE);
        }

        System.out.println("Indexes ready for usage");
        AudioMatcher audioMatcher = new AudioMatcher(audioIndex);
        MelodyMatcher melodyMatcher = new MelodyMatcher(melodyIndex);
        HybridEngine engine = new HybridEngine(audioMatcher, melodyMatcher);

        try {
            WebServer webServer = new WebServer(engine);
            webServer.start();
        } catch (Exception e) {
            System.err.println("Error starting server: " + e.getMessage());
        }
    }

    private static InvertedIndex buildAudioIndexWithSPIMI() {
        FingerprintExtractor audioExtractor = new FingerprintExtractor();
        SPIMIbuilder spimiBuilder = new SPIMIbuilder();
        int globalSongId = 1;

        File audioFolder = new File(AUDIO_DIR);
        if (audioFolder.exists() && audioFolder.isDirectory()) {
            File[] mp3Files = audioFolder.listFiles((dir, name) -> name.endsWith(".mp3"));
            if (mp3Files != null) {
                for (File mp3 : mp3Files) {
                    String songName = mp3.getName().replace(".mp3", "");
                    System.out.println("[AUDIO] Indexing: " + songName);
                    spimiBuilder.addSong(globalSongId, songName);
                    audioExtractor.processAudio(mp3, globalSongId, spimiBuilder);
                    globalSongId++;
                }
            }
        } else {
            System.err.println("Could not find audio directory: " + AUDIO_DIR);
        }
        System.out.println("[SPIMI] Finalizing audio index...");
        return spimiBuilder.buildInvertedIndex();
    }

    private static void buildMelodyIndex(MelodyIndex melodyIndex) {
        MidiProcessor midiProcessor = new MidiProcessor();
        File midiFolder = new File(MIDI_DIR);
        int[] idCounter = {100000};
        indexMidiRecursive(midiFolder, midiProcessor, melodyIndex, idCounter);
    }

    private static void indexMidiRecursive(File folder, MidiProcessor processor,
                                           MelodyIndex index, int[] idCounter) {
        if (!folder.exists() || !folder.isDirectory()) return;
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                indexMidiRecursive(file, processor, index, idCounter);
            } else if (file.getName().endsWith(".mid") || file.getName().endsWith(".midi")) {
                String songName = folder.getName() + " - "
                        + file.getName().replace(".mid", "").replace(".midi", "");
                System.out.println("[MIDI] Indexing: " + songName);
                index.addSong(idCounter[0], songName);
                processor.processMidiFile(file, idCounter[0], index);
                idCounter[0]++;
            }
        }
    }
}