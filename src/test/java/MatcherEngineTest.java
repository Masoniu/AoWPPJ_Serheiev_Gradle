import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@DisplayName("Test search engines and matcher")
class MatcherEngineTest {

    @Test
    @Tag("search")
    @DisplayName("AudioMatcher. java testing empty query")
    void testAudioMatcherEmptyQuery() {
        InvertedIndex emptyIndex = new InvertedIndex();
        AudioMatcher matcher = new AudioMatcher(emptyIndex);
        SearchResult result = matcher.findMatch(List.of());

        assertEquals("Unknown", result.songName, "Empty query should return Unknown");
        assertEquals(0, result.score, "For empty query result should be 0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"U", "UD", "UUDD", "S"})
    @Tag("search")
    @DisplayName("MelodyMatcher.java rejection for too short Parsons contour")
    void testMelodyMatcherShortQueries(String shortContour) {
        MelodyIndex emptyIndex = new MelodyIndex();
        MelodyMatcher matcher = new MelodyMatcher(emptyIndex);

        SearchResult result = matcher.findMatch(shortContour);

        assertEquals("Recording too short", result.songName, "Matcher should ignore queries shorter than k-gram");
    }

    @ParameterizedTest
    @CsvSource({
            "Never Gonna Give You Up, 250, 'Found: Never Gonna Give You Up (Score: 250)'",
            "Unknown, 0, 'Track not found'",
            "Bohemian Rhapsody, 14, 'Found: Bohemian Rhapsody (Score: 14)'"
    })
    @Tag("core")
    @DisplayName("SearchResult.java result formating validation")
    void testSearchResultFormatting(String name, int score, String expectedOutput) {
        SearchResult result = new SearchResult(name, score);
        assertEquals(expectedOutput, result.toString(), "toString() forms wrong output");
    }

    @TestFactory
    @Tag("core")
    @DisplayName("Checking IndexStorage for reliability in missing files case")
    Stream<DynamicTest> dynamicTestIndexStorageMissingFiles() {
        String[] fakePaths = {
                "missing_audio_index.dat",
                "deleted_melody_index.dat",
                "fake_dir/fake_index.bin"
        };

        return Stream.of(fakePaths).map(path -> dynamicTest(
                "Trying to load nonexistent file: " + path,
                () -> {
                    Object result = IndexStorage.load(path);
                    assertNull(result, "IndexStorage.load should return null");
                }
        ));
    }

    @Test
    @Tag("integration")
    @DisplayName("Index loading and integrity check")
    void testRealIndexIntegrity() {
        File indexFile = new File("audio_index.dat");
        Assumptions.assumeTrue(indexFile.exists() && indexFile.length() > 0, "File audio_index.dat absent, loading test aborted");
        InvertedIndex loadedIndex = (InvertedIndex) IndexStorage.load("audio_index.dat");

        assertNotNull(loadedIndex, "Index could not be loaded from existing file");
        assertTrue(loadedIndex.getUniqueHashesCount() >= 0, "Hashes count can not be negative");
    }
}