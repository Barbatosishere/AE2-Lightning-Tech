package com.moakiee.ae2lt.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

class WirelessBatchCadenceTest {
    private static final String TARGET = "target";
    private final IPatternDetails pattern = new EmptyPattern();

    @Test
    void growingChunksCreateMultiTickCoverage() {
        var cadence = new WirelessBatchCadence<String>();

        assertEquals(1, success(cadence, 0L, 1));
        assertEquals(1, success(cadence, 1L, 1));
        assertEquals(1, success(cadence, 2L, 2));
        assertEquals(1, success(cadence, 3L, 4));
        assertEquals(2, success(cadence, 4L, 8));
    }

    @Test
    void rejectionMakesFutureRefillsMoreConservative() {
        var cadence = new WirelessBatchCadence<String>();
        success(cadence, 0L, 1);
        success(cadence, 1L, 1);
        success(cadence, 2L, 2);
        success(cadence, 3L, 4);
        assertEquals(2, success(cadence, 4L, 8));

        cadence.recordFailure(TARGET, pattern, 5L);
        int recoveredCoverage = success(cadence, 9L, 4);

        assertTrue(recoveredCoverage >= 2);
    }

    @Test
    void idleHistoryResetsToOneCopyPerTickBaseline() {
        var cadence = new WirelessBatchCadence<String>();
        success(cadence, 0L, 1);
        success(cadence, 1L, 1);
        success(cadence, 2L, 2);
        success(cadence, 3L, 4);

        int coverage = success(cadence, 103L, 100);

        assertEquals(75, coverage);
    }

    private int success(
            WirelessBatchCadence<String> cadence,
            long tick,
            int copies) {
        return cadence.recordSuccess(
                TARGET,
                pattern,
                tick,
                copies,
                true,
                false);
    }

    private static final class EmptyPattern implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public List<GenericStack> getOutputs() {
            return List.of();
        }

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("third-party equality must not run");
        }

        @Override
        public int hashCode() {
            return 31;
        }
    }
}
