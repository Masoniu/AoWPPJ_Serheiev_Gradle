import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Serheiev Maksym
 */
public class InvertedIndex implements Serializable, FingerprintStore {
    @Serial
    private static final long serialVersionUID = 2L;

    private final Map<Long, int[]> index;
    private final Map<Integer, String> songNames;

    public InvertedIndex() {
        this.index = new ConcurrentHashMap<>();
        this.songNames = new ConcurrentHashMap<>();
    }

    @Override
    public void addFingerprint(long hash, int songId, int offset) {
        index.compute(hash, (k, existing) -> {
            if (existing == null) return new int[]{songId, offset};
            int[] grown = Arrays.copyOf(existing, existing.length + 2);
            grown[existing.length] = songId;
            grown[existing.length + 1] = offset;
            return grown;
        });
    }

    public void setPoints(long hash, int[] packed) {
        index.put(hash, packed);
    }

    public void addSong(int songId, String songName) {
        songNames.put(songId, songName);
    }

    public int[] getPackedPoints(long hash) {
        return index.get(hash);
    }

    public List<DataPoint> getPoints(long hash) {
        int[] packed = index.get(hash);
        if (packed == null) return Collections.emptyList();
        List<DataPoint> result = new ArrayList<>(packed.length >> 1);
        for (int i = 0; i < packed.length; i += 2) {
            result.add(new DataPoint(packed[i], packed[i + 1]));
        }
        return result;
    }

    @Override
    public String getSongName(int songId) {
        return songNames.getOrDefault(songId, "Unknown track");
    }

    public int getUniqueHashesCount() {
        return index.size();
    }
}