package com.mootmaker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Temporary. Deliberately fails, to prove that branch protection actually blocks a merge
 * rather than merely running the check (see the Definition of done in
 * mootmaker/designs/ci-cd-pipeline.md). This file is never merged - the PR carrying it is
 * closed once the block is confirmed.
 */
class GateProofTest {

    @Test
    void deliberatelyFails() {
        assertEquals(1, 2, "intentional failure proving the required check blocks merging");
    }
}
