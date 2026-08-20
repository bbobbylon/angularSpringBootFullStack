package com.bob.angularspringbootfullstack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortUtilsTest {

    private static final Set<String> ALLOWED = Set.of("customerName", "status");

    @Test
    @DisplayName("an absent sort param resolves to unsorted")
    void absentParamIsUnsorted() {
        assertTrue(SortUtils.resolveSort(Optional.empty(), ALLOWED).isUnsorted());
    }

    @Test
    @DisplayName("a blank sort param resolves to unsorted")
    void blankParamIsUnsorted() {
        assertTrue(SortUtils.resolveSort(Optional.of("  "), ALLOWED).isUnsorted());
    }

    @Test
    @DisplayName("a field outside the allow-list resolves to unsorted rather than erroring")
    void disallowedFieldIsUnsorted() {
        assertTrue(SortUtils.resolveSort(Optional.of("password,asc"), ALLOWED).isUnsorted());
    }

    @Test
    @DisplayName("a bare field with no direction defaults to ascending")
    void bareFieldDefaultsAscending() {
        Sort sort = SortUtils.resolveSort(Optional.of("customerName"), ALLOWED);
        assertEquals(Sort.by(Sort.Direction.ASC, "customerName"), sort);
    }

    @Test
    @DisplayName("an explicit desc direction is honored")
    void descDirectionIsHonored() {
        Sort sort = SortUtils.resolveSort(Optional.of("status,desc"), ALLOWED);
        assertEquals(Sort.by(Sort.Direction.DESC, "status"), sort);
    }

    @Test
    @DisplayName("direction is case-insensitive")
    void directionIsCaseInsensitive() {
        Sort sort = SortUtils.resolveSort(Optional.of("status,DESC"), ALLOWED);
        assertEquals(Sort.by(Sort.Direction.DESC, "status"), sort);
    }

    @Test
    @DisplayName("an unrecognized direction falls back to ascending rather than erroring")
    void unrecognizedDirectionFallsBackToAscending() {
        Sort sort = SortUtils.resolveSort(Optional.of("status,sideways"), ALLOWED);
        assertEquals(Sort.by(Sort.Direction.ASC, "status"), sort);
    }
}
