import java.io.*;
import java.util.*;

/**
 * @author Serheiev Maksym
 */
public class SPIMIbuilder implements FingerprintStore {
    private static final double THRESHOLD = 0.70;
    private static final int CHECK_INTERVAL = 10000;
    private static final Runtime RT = Runtime.getRuntime();
    private static final String TEMP_DIR = "spimi_blocks";

    private final Map<Long, List<DataPoint>> buffer = new HashMap<>();
    private int entryCount = 0;
    private final List<File> blockFiles = new ArrayList<>();
    private final Map<Integer, String> songNames  = new HashMap<>();

    public void addSong(int songId, String songName) {
        songNames.put(songId, songName);
    }

    @Override
    public void addFingerprint(long hash, int songId, int offset) {
        buffer.computeIfAbsent(hash, k -> new ArrayList<>()).add(new DataPoint(songId, offset));
        entryCount++;
        if (entryCount % CHECK_INTERVAL == 0 && isHeapCritical()) {
            writeBlock();
        }
    }

    @Override
    public String getSongName(int songId) {
        return songNames.getOrDefault(songId, "Unknown track");
    }

    private boolean isHeapCritical() {
        long used = RT.totalMemory() - RT.freeMemory();
        long max = RT.maxMemory();
        double heapPct = (double) used / max;
        if (heapPct >= THRESHOLD) {
            System.out.printf("[SPIMI] Heap %.1f%% -> writing block to disk%n", heapPct * 100);
            return true;
        }
        return false;
    }

    private void writeBlock() {
        if (buffer.isEmpty()) return;
        new File(TEMP_DIR).mkdirs();
        File blockFile = new File(TEMP_DIR, "block_" + blockFiles.size() + ".bin");
        blockFiles.add(blockFile);

        List<Map.Entry<Long, List<DataPoint>>> sorted = new ArrayList<>(buffer.entrySet());
        sorted.sort(Map.Entry.comparingByKey());

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(blockFile), 1 << 16))) {
            for (Map.Entry<Long, List<DataPoint>> entry : sorted) {
                long hash = entry.getKey();
                for (DataPoint dp : entry.getValue()) {
                    dos.writeLong(hash);
                    dos.writeInt(dp.getSongId());
                    dos.writeInt(dp.getOffset());
                }
            }
        } catch (IOException e) {
            System.err.println("[SPIMI] Error writing block file " + blockFile.getName() + ": " + e.getMessage());
        }
        System.out.printf("[SPIMI] Written block %d (%,d unique hashes, %,d KB) on disk%n",
                blockFiles.size() - 1, buffer.size(), blockFile.length() / 1024);
        buffer.clear();
        entryCount = 0;
        System.gc();
    }

    public InvertedIndex buildInvertedIndex() {
        if (!buffer.isEmpty()) writeBlock();

        InvertedIndex index = new InvertedIndex();
        for (Map.Entry<Integer, String> e : songNames.entrySet()) {
            index.addSong(e.getKey(), e.getValue());
        }
        if (blockFiles.isEmpty()) {
            System.out.println("[SPIMI] No block files found");
            return index;
        }
        System.out.println("[SPIMI] Found " + blockFiles.size() +
                " block files, starting highly-optimized memory merge");
        mergeBlocks(index);
        cleanupTempFiles();
        System.out.println("[SPIMI] Merge completed, unique hashes: " + index.getUniqueHashesCount());
        return index;
    }

    private void mergeBlocks(InvertedIndex index) {
        int n = blockFiles.size();
        DataInputStream[] readers = new DataInputStream[n];
        PriorityQueue<long[]> pq  =
                new PriorityQueue<>(Math.max(n, 1), Comparator.comparingLong(a -> a[0]));

        for (int i = 0; i < n; i++) {
            try {
                readers[i] = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(blockFiles.get(i)), 1 << 16));
                long[] entry = readNextEntry(readers[i], i);
                if (entry != null) pq.offer(entry);
            } catch (IOException e) {
                System.err.println("[SPIMI] Error opening block " + i + ": " + e.getMessage());
            }
        }

        long currentHash = -1;
        int[] packedBuf = new int[64];
        int packedLen = 0;
        boolean isFirst = true;

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int  readerIdx = (int) top[3];
            long hash = top[0];
            int  songId = (int) top[1];
            int  offset = (int) top[2];

            if (isFirst) {
                currentHash = hash;
                isFirst = false;
            } else if (hash != currentHash) {
                index.setPoints(currentHash, Arrays.copyOf(packedBuf, packedLen));
                currentHash = hash;
                packedLen = 0;
            }
            if (packedLen + 2 > packedBuf.length) {
                packedBuf = Arrays.copyOf(packedBuf, packedBuf.length * 2);
            }
            packedBuf[packedLen++] = songId;
            packedBuf[packedLen++] = offset;

            long[] next = readNextEntry(readers[readerIdx], readerIdx);
            if (next != null) pq.offer(next);
        }
        if (packedLen > 0) {
            index.setPoints(currentHash, Arrays.copyOf(packedBuf, packedLen));
        }

        for (DataInputStream r : readers) {
            try { if (r != null) r.close(); } catch (IOException ignored) {}
        }
    }

    private long[] readNextEntry(DataInputStream dis, int readerIdx) {
        try {
            long hash = dis.readLong();
            int  songId = dis.readInt();
            int  offset = dis.readInt();
            return new long[]{hash, songId, offset, readerIdx};
        } catch (EOFException e) {
            return null;
        } catch (IOException e) {
            System.err.println("[SPIMI] Error reading block " + readerIdx);
            return null;
        }
    }

    private void cleanupTempFiles() {
        for (File f : blockFiles) {
            if (f.exists() && !f.delete()) f.deleteOnExit();
        }
        File tempDir = new File(TEMP_DIR);
        String[] remaining = tempDir.list();
        if (remaining == null || remaining.length == 0) tempDir.delete();
        System.out.println("[SPIMI] Temporary files deleted");
    }
}