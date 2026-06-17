package com.audioengine;

/**
 * @author Serheiev Maksym
 */
public interface FingerprintStore {
    void addFingerprint(long hash, int songId, int offset);
    String getSongName(int songId);
}
