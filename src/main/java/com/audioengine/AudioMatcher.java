package com.audioengine;

import java.util.*;

/**
 * @author Serheiev Maksym
 */
class QueryPoint {
    public final long hash;
    public final int offset;

    public QueryPoint(long hash, int offset) {
        this.hash = hash;
        this.offset = offset;
    }
}

class SearchResult {
    public final String songName;
    public final int score;

    public SearchResult(String songName, int score) {
        this.songName = songName;
        this.score = score;
    }

    @Override
    public String toString() {
        if (score == 0) return "Track not found";
        return "Found: " + songName + " (Score: " + score + ")";
    }
}

/**
 * @author Serheiev Maksym
 */
public class AudioMatcher {
    private final InvertedIndex index;
    private static final int MATCH_THRESHOLD = 15;

    public AudioMatcher(InvertedIndex index) {
        this.index = index;
    }

    public SearchResult findMatch(List<QueryPoint> queryPoints) {
        int foundInIndex = 0;
        for (QueryPoint qp : queryPoints) {
            if (index.getPackedPoints(qp.hash) != null) foundInIndex++;
        }
        System.out.println("Query hashes found in index: " + foundInIndex + "/" + queryPoints.size());

        // Encode (songId, delta) as one long: high 32 bits = songId, low 32 = delta.
        // LongIntMap uses parallel long[]/int[] arrays — zero Integer boxing.
        LongIntMap scores = new LongIntMap(1 << 17);

        for (QueryPoint queryPoint : queryPoints) {
            int[] packed = index.getPackedPoints(queryPoint.hash);
            if (packed == null) continue;
            for (int i = 0; i < packed.length; i += 2) {
                int songId = packed[i];
                int delta = packed[i + 1] - queryPoint.offset;
                long key = ((long) songId << 32) | (delta & 0xFFFFFFFFL);
                scores.increment(key);
            }
        }

        return getBest(scores);
    }

    private SearchResult getBest(LongIntMap scores) {
        int bestSongId = -1;
        int maxScore = 0;
        LongIntMap.Iter it = scores.iter();
        while (it.advance()) {
            int songId = (int) (it.key() >>> 32);
            int score = it.value();
            if (score > maxScore) {
                maxScore = score;
                bestSongId = songId;
            }
        }
        System.out.println("Best song: " + index.getSongName(bestSongId) + " | Max delta score: " + maxScore);
        if (bestSongId != -1 && maxScore >= MATCH_THRESHOLD) {
            return new SearchResult(index.getSongName(bestSongId), maxScore);
        }
        return new SearchResult("Unknown", 0);
    }

    /**
     * @author Serheiev Maksym
     */
    private static class LongIntMap {
        private static final long EMPTY = Long.MIN_VALUE;

        private long[] keys;
        private int[] vals;
        private int size;
        private int mask;

        LongIntMap(int initialCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(initialCapacity * 2 - 1) << 1);
            keys = new long[cap];
            vals = new int[cap];
            Arrays.fill(keys, EMPTY);
            mask = cap - 1;
        }

        void increment(long key) {
            if (size >= keys.length * 0.7) resize();
            int slot = probe(key);
            if (keys[slot] == EMPTY) {
                keys[slot] = key;
                vals[slot] = 1;
                size++;
            } else {
                vals[slot]++;
            }
        }

        private int probe(long key) {
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - 31)) & mask;
            while (keys[slot] != EMPTY && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            return slot;
        }

        private void resize() {
            long[] oldKeys = keys;
            int[] oldVals = vals;
            int newCap = keys.length * 2;
            keys = new long[newCap];
            vals = new int[newCap];
            Arrays.fill(keys, EMPTY);
            mask = newCap - 1;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) {
                    int slot = probe(oldKeys[i]);
                    keys[slot] = oldKeys[i];
                    vals[slot] = oldVals[i];
                    size++;
                }
            }
        }

        Iter iter() { return new Iter(); }

        class Iter {
            private int pos = -1;
            boolean advance() {
                pos++;
                while (pos < keys.length && keys[pos] == EMPTY) pos++;
                return pos < keys.length;
            }

            long key() { return keys[pos]; }
            int value() { return vals[pos]; }
        }
    }
}