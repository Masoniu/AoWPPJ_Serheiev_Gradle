package com.audioengine;

import java.io.*;

/**
 * @author Serheiev Maksym
 */
public class MidiProcessor {
    private static final int K = 5;

    public void processMidiFile(File midiFile, int songId, MelodyIndex index) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(midiFile), 1 << 16))) {
            if (!readTag(in, "MThd")) {
                System.out.println("Skipped non-MIDI file: " + midiFile.getName());
                return;
            }
            readInt(in);
            readShort(in);
            int numTracks = readShort(in);
            readShort(in);
            char[] ring = new char[K];
            int ringHead = 0;
            int ringFill = 0;
            int notePos = 0;
            int prevPitch = -1;
            char[] kgramBuf = new char[K];

            for (int t = 0; t < numTracks; t++) {
                if (!readTag(in, "MTrk")) {
                    System.out.println("Skipped broken file: " + midiFile.getName());
                    return;
                }
                long remaining = Integer.toUnsignedLong(readInt(in));
                int runningStatus = 0;

                while (remaining > 0) {

                    remaining -= skipVLQ(in);

                    int firstByte = in.readUnsignedByte();
                    remaining--;

                    if (firstByte == 0xFF) {
                        in.readUnsignedByte();
                        remaining--;
                        long len = readVLQ(in);
                        remaining -= vlqByteCount(len);
                        skipFully(in, len);
                        remaining -= len;
                        runningStatus = 0;
                        continue;
                    }

                    if (firstByte == 0xF0 || firstByte == 0xF7) {
                        long len = readVLQ(in);
                        remaining -= vlqByteCount(len);
                        skipFully(in, len);
                        remaining -= len;
                        runningStatus = 0;
                        continue;
                    }

                    int status, dataByte1;
                    if ((firstByte & 0x80) != 0) {
                        status = firstByte;
                        runningStatus = status;
                        dataByte1 = in.readUnsignedByte();
                        remaining--;
                    } else {
                        status = runningStatus;
                        dataByte1 = firstByte;
                    }

                    int statusType = status & 0xF0;

                    switch (dataByteCount(statusType)) {
                        case 2 -> {
                            int dataByte2 = in.readUnsignedByte();
                            remaining--;

                            if (statusType == 0x90 && dataByte2 > 0) {
                                int pitch = dataByte1;
                                if (prevPitch != -1) {
                                    char dir;
                                    if (pitch > prevPitch) dir = 'U';
                                    else if (pitch < prevPitch) dir = 'D';
                                    else dir = 'S';

                                    ring[ringHead] = dir;
                                    ringHead = (ringHead + 1) % K;
                                    if (ringFill < K) ringFill++;

                                    if (ringFill == K) {
                                        int start = ringHead;
                                        for (int k = 0; k < K; k++) {
                                            kgramBuf[k] = ring[(start + k) % K];
                                        }
                                        index.addKGram(new String(kgramBuf), songId, notePos);
                                        notePos++;
                                    }
                                }
                                prevPitch = pitch;
                            }
                        }
                        case 1 -> {}
                    }
                }
            }

        } catch (EOFException e) {
            System.out.println("Skipped truncated file: " + midiFile.getName());
        } catch (Exception e) {
            System.out.println("Skipped broken file: " + midiFile.getName());
        }
    }

    private boolean readTag(DataInputStream in, String tag) throws IOException {
        byte[] buf = new byte[4];
        int read = in.read(buf);
        if (read != 4) return false;
        return new String(buf, java.nio.charset.StandardCharsets.US_ASCII).equals(tag);
    }

    private int readInt(DataInputStream in) throws IOException { return in.readInt(); }
    private int readShort(DataInputStream in) throws IOException { return in.readUnsignedShort(); }

    private long readVLQ(DataInputStream in) throws IOException {
        long value = 0;
        int b;
        do {
            b = in.readUnsignedByte();
            value = (value << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return value;
    }

    private int skipVLQ(DataInputStream in) throws IOException {
        int bytes = 0, b;
        do { b = in.readUnsignedByte(); bytes++; } while ((b & 0x80) != 0);
        return bytes;
    }

    private int vlqByteCount(long value) {
        if (value < 0x80L) return 1;
        if (value < 0x4000L) return 2;
        if (value < 0x200000L) return 3;
        if (value < 0x10000000L) return 4;
        return 5;
    }

    private void skipFully(DataInputStream in, long n) throws IOException {
        long rem = n;
        while (rem > 0) {
            long s = in.skip(rem);
            if (s <= 0) throw new EOFException("Unexpected end of MIDI stream");
            rem -= s;
        }
    }

    private int dataByteCount(int statusType) {
        return switch (statusType) {
            case 0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2;
            case 0xC0, 0xD0 -> 1;
            default -> 0;
        };
    }
}