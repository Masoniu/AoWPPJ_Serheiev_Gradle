import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Serheiev Maksym
 */
public class MelodyIndex implements Serializable {
    @Serial
    private static final long serialVersionUID = 6L;

    private static final double THRESHOLD = 0.85;
    private static final int CHECK_INTERVAL = 50_000;
    private static final int MAX_FANS = 128;
    private static final String TEMP_DIR = "spimi_melody_blocks";
    private static final String FINAL_FILE = "melody_postings.bin";
    private static final Runtime RT = Runtime.getRuntime();

    private transient Map<String, ArrayList<Integer>> buildBuffer = new HashMap<>();
    private transient int entryCount = 0;
    private transient List<File> blockFiles = new ArrayList<>();
    private Map<String, Long> offsets = null;

    private final Map<Integer, String> songNames = new ConcurrentHashMap<>();

    public void addSong(int songId, String songName) {
        songNames.put(songId, songName);
    }

    public void addKGram(String kgram, int songId, int notePos) {
        buildBuffer.computeIfAbsent(kgram, k -> new ArrayList<>()).add(songId);
        buildBuffer.get(kgram).add(notePos);
        entryCount++;
        if (entryCount % CHECK_INTERVAL == 0 && isHeapCritical()) {
            writeBlock();
        }
    }

    public void freeze() {
        if (!buildBuffer.isEmpty()) writeBlock();
        buildBuffer = null;
        if (blockFiles.isEmpty()) {
            offsets = new HashMap<>();
            return;
        }
        System.out.println("[MELODY-SPIMI] Merging " + blockFiles.size() + " block(s)...");
        while (blockFiles.size() > MAX_FANS) {
            blockFiles = mergePassToFiles(blockFiles);
        }
        offsets = mergeFinalToDisk(blockFiles, new File(FINAL_FILE));
        cleanupDir();
        System.out.println("[MELODY-SPIMI] Done. Unique k-grams: " + offsets.size());
    }

    public List<DataPoint> getPoints(String kgram) {
        if (offsets == null) freeze();
        Long fileOffset = offsets.get(kgram);
        if (fileOffset == null) return Collections.emptyList();
        try (RandomAccessFile raf = new RandomAccessFile(FINAL_FILE, "r")) {
            raf.seek(fileOffset);
            int kgramLen = raf.readUnsignedShort();
            raf.skipBytes(kgramLen * 2);
            int count = raf.readInt();
            List<DataPoint> result = new ArrayList<>(count >> 1);
            for (int i = 0; i < count; i += 2) {
                result.add(new DataPoint(raf.readInt(), raf.readInt()));
            }
            return result;
        } catch (IOException e) {
            System.err.println("[MELODY] Error reading postings for: " + kgram);
            return Collections.emptyList();
        }
    }

    public String getSongName(int songId) {
        return songNames.getOrDefault(songId, "Unknown track");
    }

    private boolean isHeapCritical() {
        long used = RT.totalMemory() - RT.freeMemory();
        double pct = (double) used / RT.maxMemory();
        if (pct >= THRESHOLD) {
            System.out.printf("[MELODY-SPIMI] Heap %.1f%% -> writing block%n", pct * 100);
            return true;
        }
        return false;
    }

    private void writeBlock() {
        if (buildBuffer.isEmpty()) return;
        new File(TEMP_DIR).mkdirs();
        File f = new File(TEMP_DIR, "block_" + blockFiles.size() + ".bin");
        blockFiles.add(f);

        List<Map.Entry<String, ArrayList<Integer>>> sorted = new ArrayList<>(buildBuffer.entrySet());
        sorted.sort(Map.Entry.comparingByKey());

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            for (Map.Entry<String, ArrayList<Integer>> e : sorted) {
                ArrayList<Integer> list = e.getValue();
                for (int i = 0; i < list.size(); i += 2) {
                    writeRecord(dos, e.getKey(), new int[]{list.get(i), list.get(i + 1)}, 2);
                }
            }
        } catch (IOException e) {
            System.err.println("[MELODY-SPIMI] Error writing block: " + e.getMessage());
        }
        System.out.printf("[MELODY-SPIMI] Block %d written (%,d unique k-grams)%n",
                blockFiles.size() - 1, buildBuffer.size());
        buildBuffer.clear();
        entryCount = 0;
        System.gc();
    }

    private List<File> mergePassToFiles(List<File> files) {
        List<File> outputs = new ArrayList<>();
        for (int start = 0; start < files.size(); start += MAX_FANS) {
            List<File> batch = files.subList(start, Math.min(start + MAX_FANS, files.size()));
            File out = new File(TEMP_DIR, "pass_" + outputs.size() + ".bin");
            mergeToFile(batch, out);
            for (File src : batch) src.delete();
            outputs.add(out);
        }
        System.out.println("[MELODY-SPIMI] Intermediate pass: " + outputs.size() + " file(s) remaining");
        return outputs;
    }

    private void mergeToFile(List<File> batch, File out) {
        DataInputStream[] readers = openReaders(batch);
        PriorityQueue<Object[]> pq = seedPQ(readers);
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            drain(pq, readers, (kgram, packed, len) -> writeRecord(dos, kgram, packed, len));
        } catch (IOException e) {
            System.err.println("[MELODY-SPIMI] Error writing merged file: " + e.getMessage());
        }
        closeAll(readers);
    }

    private Map<String, Long> mergeFinalToDisk(List<File> files, File out) {
        DataInputStream[] readers = openReaders(files);
        PriorityQueue<Object[]> pq = seedPQ(readers);
        Map<String, Long> offsetMap = new HashMap<>();

        try (CountingOutputStream cos = new CountingOutputStream(
                new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            DataOutputStream dos = new DataOutputStream(cos);
            drain(pq, readers, (kgram, packed, len) -> {
                offsetMap.put(kgram, cos.getCount());
                writeRecord(dos, kgram, packed, len);
            });
            dos.flush();
        } catch (IOException e) {
            System.err.println("[MELODY-SPIMI] Error writing final file: " + e.getMessage());
        }
        closeAll(readers);
        return offsetMap;
    }

    private void drain(PriorityQueue<Object[]> pq, DataInputStream[] readers,
                       PostingConsumer consumer) {
        String currentKgram = null;
        int[] packedBuf = new int[64];
        int packedLen = 0;

        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            String kgram = (String) top[0];
            int[] payload = (int[]) top[1];
            int ri = (int) top[2];

            if (!kgram.equals(currentKgram)) {
                if (currentKgram != null) {
                    try { consumer.accept(currentKgram, packedBuf, packedLen); }
                    catch (IOException e) { System.err.println("[MELODY-SPIMI] Write error"); }
                }
                currentKgram = kgram;
                packedLen = 0;
            }
            if (packedLen + payload.length > packedBuf.length) {
                packedBuf = Arrays.copyOf(packedBuf,
                        Math.max(packedBuf.length * 2, packedLen + payload.length));
            }
            System.arraycopy(payload, 0, packedBuf, packedLen, payload.length);
            packedLen += payload.length;

            Object[] next = readNextEntry(readers[ri], ri);
            if (next != null) pq.offer(next);
        }
        if (currentKgram != null && packedLen > 0) {
            try { consumer.accept(currentKgram, packedBuf, packedLen); }
            catch (IOException e) { System.err.println("[MELODY-SPIMI] Write error"); }
        }
    }

    @FunctionalInterface
    private interface PostingConsumer {
        void accept(String kgram, int[] packed, int len) throws IOException;
    }

    private Object[] readNextEntry(DataInputStream dis, int ri) {
        try {
            int kgramLen = dis.readUnsignedShort();
            char[] chars = new char[kgramLen];
            for (int i = 0; i < kgramLen; i++) chars[i] = dis.readChar();
            int count = dis.readInt();
            int[] payload = new int[count];
            for (int i = 0; i < count; i++) payload[i] = dis.readInt();
            return new Object[]{new String(chars), payload, ri};
        } catch (EOFException e) {
            return null;
        } catch (IOException e) {
            System.err.println("[MELODY-SPIMI] Read error on reader " + ri);
            return null;
        }
    }

    private void writeRecord(DataOutputStream dos, String kgram,
                             int[] packed, int len) throws IOException {
        dos.writeShort(kgram.length());
        for (char c : kgram.toCharArray()) dos.writeChar(c);
        dos.writeInt(len);
        for (int i = 0; i < len; i++) dos.writeInt(packed[i]);
    }

    private DataInputStream[] openReaders(List<File> files) {
        DataInputStream[] readers = new DataInputStream[files.size()];
        for (int i = 0; i < files.size(); i++) {
            try {
                readers[i] = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(files.get(i)), 1024));
            } catch (IOException e) {
                System.err.println("[MELODY-SPIMI] Cannot open file " + i);
            }
        }
        return readers;
    }

    private PriorityQueue<Object[]> seedPQ(DataInputStream[] readers) {
        PriorityQueue<Object[]> pq = new PriorityQueue<>(
                Math.max(readers.length, 1), Comparator.comparing(a -> (String) a[0]));
        for (int i = 0; i < readers.length; i++) {
            if (readers[i] == null) continue;
            Object[] entry = readNextEntry(readers[i], i);
            if (entry != null) pq.offer(entry);
        }
        return pq;
    }

    private void closeAll(DataInputStream[] readers) {
        for (DataInputStream r : readers) {
            try { if (r != null) r.close(); } catch (IOException ignored) {}
        }
    }

    private void cleanupDir() {
        File dir = new File(TEMP_DIR);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
        System.out.println("[MELODY-SPIMI] Temporary files deleted");
    }

    private static class CountingOutputStream extends FilterOutputStream {
        private long count = 0;

        CountingOutputStream(OutputStream out) { super(out); }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long getCount() { return count; }
    }
}