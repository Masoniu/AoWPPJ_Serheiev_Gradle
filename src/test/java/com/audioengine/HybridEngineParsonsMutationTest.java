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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testing of Parsons contour translation with mutations")
class HybridEngineParsonsMutationTest {
    @Mock
    private AudioMatcher audioMatcher;
    @Mock
    private MelodyMatcher melodyMatcher;
    @InjectMocks
    private HybridEngine engine;

    @Test
    @DisplayName("Weak test(checks only growing values)")
    void weakTestFailingFromMutation() {
        when(audioMatcher.findMatch(anyList())).thenReturn(new SearchResult("Unknown", 0));
        when(melodyMatcher.findMatch(anyString())).thenReturn(new SearchResult("Found", 100));
        SearchResult result = engine.performCascadeSearch(List.of(), List.of(60, 62, 64));
        assertEquals("Found", result.songName);
    }

    @Test
    @DisplayName("Strong test(covers all if/else branches and their edge cases)")
    void strongTestKillingMutations() {
        when(audioMatcher.findMatch(anyList())).thenReturn(new SearchResult("Unknown", 0));
        when(melodyMatcher.findMatch("UDS")).thenReturn(new SearchResult("Perfect Match", 150));
        when(melodyMatcher.findMatch("")).thenReturn(new SearchResult("Track not found", 0));

        SearchResult resultEmpty = engine.performCascadeSearch(List.of(), List.of(60));
        assertEquals("Track not found", resultEmpty.songName, "Should return empty contour");

        SearchResult resultComplex = engine.performCascadeSearch(List.of(), List.of(60, 65, 62, 62));
        assertEquals("Perfect Match", resultComplex.songName, "Should generate contour UDS");
    }
}