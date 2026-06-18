package com.audioengine;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testing InvertedIndex and data unpacking")
class InvertedIndexJTest {

    @Test
    @DisplayName("Testing validity of SearchResult")
    void testSearchResultWithSoftAssertions() {
        SearchResult result = new SearchResult("AC/DC - Thunderstruck", 450);
        SoftAssertions softly = new SoftAssertions();

        softly.assertThat(result.songName)
                .as("Name of detected track")
                .isNotBlank()
                .contains("-")
                .doesNotContain("Unknown");

        softly.assertThat(result.score)
                .as("Detection score")
                .isPositive()
                .isGreaterThanOrEqualTo(200);

        softly.assertAll();
    }

    @Test
    @DisplayName("Testing unpacked list of DataPoints")
    void testDataPointUnpacking() {
        InvertedIndex index = new InvertedIndex();

        long collisionHash = 987654321L;
        index.addFingerprint(collisionHash, 10, 500);
        index.addFingerprint(collisionHash, 10, 510);
        index.addFingerprint(collisionHash, 25, 100);

        List<DataPoint> points = index.getPoints(collisionHash);

        assertThat(points)
                .isNotEmpty()
                .hasSize(3)
                .doesNotContainNull();

        assertThat(points)
                .extracting(DataPoint::getSongId)
                .contains(10, 25)
                .doesNotContain(99);

        assertThat(points)
                .filteredOn(dp -> dp.getOffset() <= 150)
                .hasSize(1)
                .first()
                .extracting(DataPoint::getSongId)
                .isEqualTo(25);
    }
}