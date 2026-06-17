package com.audioengine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Serheiev Maksym
 */
public class MelodyMatcher {
    private final MelodyIndex index;
    private static final int K = 5;

    public MelodyMatcher(MelodyIndex index) {
        this.index = index;
    }

    public SearchResult findMatch(String query){
        if(query == null||query.length() < K){
            return new SearchResult("Recording too short", 0);
        }
        Map<Integer, Integer> scores = new HashMap<>();
        for(int i=0; i<=query.length()-K; i++){
            String kgram = query.substring(i, i+K);
            List<DataPoint> simInDb = index.getPoints(kgram);
            for(DataPoint dp : simInDb){
                int songId = dp.getSongId();
                scores.put(songId, scores.getOrDefault(songId, 0) + 1);
            }
        }
        return getBest(scores);
    }

    private SearchResult getBest(Map<Integer, Integer> scores){
        int bestId = -1;
        int maxScore = -1;
        for(Map.Entry<Integer, Integer> entry : scores.entrySet()){
            if(entry.getValue() > maxScore){
                maxScore = entry.getValue();
                bestId = entry.getKey();
            }
        }
        if(bestId != -1 && maxScore >= 2){
            String songName = index.getSongName(bestId);
            return new SearchResult(songName, maxScore);
        }
        return new SearchResult("Unfamiliar melody", 0);
    }
}
