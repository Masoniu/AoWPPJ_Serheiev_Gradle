package com.audioengine;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author Serheiev Maksym
 */
public class DataPoint implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final int songId;
    private final int offset;

    public DataPoint(int songId, int offset) {
        this.songId = songId;
        this.offset = offset;
    }

    public int getSongId() {
        return songId;
    }

    public int getOffset() {
        return offset;
    }

    @Override
    public String toString() {
        return "[SongID=" + songId + ", Time=" + offset + "]";
    }
}
