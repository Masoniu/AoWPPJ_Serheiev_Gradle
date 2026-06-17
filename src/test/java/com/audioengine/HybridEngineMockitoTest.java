package com.audioengine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Mockito-testing of HybridEngine")
class HybridEngineMockitoTest {
    @Mock
    private AudioMatcher audioMatcher;
    @Mock
    private MelodyMatcher melodyMatcher;
    @InjectMocks
    private HybridEngine engine;

    @Test
    @DisplayName("Testing fallback with high score")
    void testCascadeSearch_AudioMatchStopsExecution() {
        when(audioMatcher.findMatch(anyList())).thenReturn(new SearchResult("Linkin Park - Numb", 250));

        SearchResult result = engine.performCascadeSearch(List.of(), List.of());
        assertEquals("Linkin Park - Numb", result.songName);
        verify(melodyMatcher, never()).findMatch(anyString());
    }

    @Test
    @DisplayName("Testing fallback with low score")
    void testCascadeSearch_FallbackToMelody() {
        when(audioMatcher.findMatch(anyList())).thenReturn(new SearchResult("Unknown", 50));
        when(melodyMatcher.findMatch(anyString())).thenReturn(new SearchResult("Queen - We Will Rock You", 120));
        SearchResult result = engine.performCascadeSearch(List.of(), List.of(60, 62, 64));

        assertEquals("Queen - We Will Rock You", result.songName);
        verify(melodyMatcher).findMatch(anyString());
    }

    @Test
    @DisplayName("Testing score too low for both algorithms")
    void testCascadeSearch_TotalFailure() {
        when(audioMatcher.findMatch(anyList())).thenReturn(new SearchResult("Unknown", 10));
        when(melodyMatcher.findMatch(anyString())).thenReturn(new SearchResult("Unfamiliar melody", 10));
        SearchResult result = engine.performCascadeSearch(List.of(), List.of(60, 62, 64));
        assertEquals("Track not found", result.songName);
    }

    @Test
    @DisplayName("Testing FingerprintStore void-methods")
    void testFingerprintStoreContract() {
        FingerprintStore mockStore = mock(FingerprintStore.class);

        mockStore.addFingerprint(1001L, 5, 10);
        mockStore.addFingerprint(1002L, 5, 15);
        mockStore.addFingerprint(9999L, 6, 20);

        verify(mockStore, times(2)).addFingerprint(anyLong(), eq(5), anyInt());
        verify(mockStore, times(1)).addFingerprint(anyLong(), eq(6), anyInt());
    }
}