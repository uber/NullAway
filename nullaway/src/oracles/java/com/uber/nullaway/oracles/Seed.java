package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;

/**
 * A set of compilation units the oracles compile together: one chain of {@code addSourceLines}
 * calls in a NullAway test, one program of {@link CombinationGenerator}, or files that {@link
 * DirectorySeeds} reads.
 *
 * @param name where the seed came from, such as the test file and the test method, as {@link
 *     SeedExtractor} forms it
 * @param sources the compilation units, in the order the test added them
 */
record Seed(String name, ImmutableList<Source> sources) {}
