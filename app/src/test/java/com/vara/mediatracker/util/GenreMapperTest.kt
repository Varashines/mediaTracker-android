package com.vara.mediatracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreMapperTest {

    @Test
    fun `splits compound genres into atomic names`() {
        assertEquals(
            listOf("Action", "Adventure", "Science Fiction"),
            GenreMapper.standardize(listOf("Action & Adventure", "Sci-Fi"))
        )
    }

    @Test
    fun `splits on slash and drops a bare and`() {
        assertEquals(
            listOf("Drama", "Thriller"),
            GenreMapper.standardize(listOf("Drama/Thriller"))
        )
        assertEquals(
            listOf("Politics", "War"),
            GenreMapper.standardize(listOf("War and Politics"))
        )
    }

    @Test
    fun `maps inconsistent naming`() {
        assertEquals(listOf("Science Fiction"), GenreMapper.standardize(listOf("science-fiction")))
        assertEquals(listOf("Science Fiction"), GenreMapper.standardize(listOf("science fiction")))
        assertEquals(listOf("TV Movie"), GenreMapper.standardize(listOf("TV Movie")))
        assertEquals(listOf("Soap Opera"), GenreMapper.standardize(listOf("Soap")))
    }

    @Test
    fun `dedupes and sorts`() {
        assertEquals(
            listOf("Drama", "Science Fiction"),
            GenreMapper.standardize(listOf("Sci-Fi", "Science Fiction", "Drama"))
        )
    }

    @Test
    fun `empty input yields nothing`() {
        assertEquals(emptyList<String>(), GenreMapper.standardize(emptyList()))
        assertEquals(emptyList<String>(), GenreMapper.standardize(listOf("  ")))
    }
}
