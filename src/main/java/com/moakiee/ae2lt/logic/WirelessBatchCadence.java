package com.moakiee.ae2lt.logic;

import java.util.HashMap;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;

/**
 * Learns a pattern-scoped wireless refill cadence from successful ownership
 * and rejected refill probes.
 *
 * <p>The estimate is deliberately runtime-only. A clean stream of full chunks
 * raises the estimated processing rate gradually; a rejection cuts it in half.
 * The resulting coverage interval tells the fair scheduler how many ticks the
 * newly owned copies should keep this target supplied. This turns a large CPU
 * request into staggered refill waves instead of an all-target scan every tick.</p>
 */
final class WirelessBatchCadence<T> {
    static final int MAX_COVERAGE_TICKS = 100;
    private static final int HISTORY_TTL = 100;
    private static final long RATE_SCALE = 1L << 10;
    private static final int REFILL_SAFETY_NUMERATOR = 3;
    private static final int REFILL_SAFETY_DENOMINATOR = 4;
    private static final long MIN_RATE = 1L;
    private static final long MAX_RATE = (long) Integer.MAX_VALUE * RATE_SCALE;

    private final Map<T, Map<IPatternDetails, State>> states = new HashMap<>();

    int recordSuccess(
            T target,
            IPatternDetails pattern,
            long gameTick,
            long ownedCopies,
            boolean acceptedFullChunk,
            boolean requestLimited) {
        if (ownedCopies <= 0L) {
            throw new IllegalArgumentException(
                    "Successful cadence samples must own at least one copy");
        }
        var state = state(target, pattern);
        state.expireIfIdle(gameTick);

        if (state.lastSuccessTick != Long.MIN_VALUE) {
            long elapsed = Math.max(1L, gameTick - state.lastSuccessTick);
            long observedRate = scaledRate(state.lastOwnedCopies, elapsed);
            if (state.rejectedSinceLastSuccess) {
                state.rate = Math.min(state.rate, observedRate);
            } else if (observedRate > state.rate) {
                state.rate = Math.min(observedRate, doubled(state.rate));
            }
        }

        if (acceptedFullChunk && !requestLimited
                && !state.rejectedSinceLastSuccess) {
            state.cleanSuccesses++;
            if (state.cleanSuccesses >= 2) {
                state.rate = doubled(state.rate);
                state.cleanSuccesses = 0;
            }
        } else {
            state.cleanSuccesses = 0;
        }

        state.lastSuccessTick = gameTick;
        state.lastOwnedCopies = ownedCopies;
        state.rejectedSinceLastSuccess = false;

        return coverageTicks(ownedCopies, state.rate);
    }

    void recordFailure(
            T target,
            IPatternDetails pattern,
            long gameTick) {
        var state = state(target, pattern);
        state.expireIfIdle(gameTick);
        state.rate = Math.max(MIN_RATE, state.rate / 2L);
        state.cleanSuccesses = 0;
        state.rejectedSinceLastSuccess = true;
    }

    void removeTarget(T target) {
        states.remove(target);
    }

    void clear() {
        states.clear();
    }

    private State state(T target, IPatternDetails pattern) {
        var byPattern = states.computeIfAbsent(
                target, ignored -> CanonicalPatternMaps.create());
        return byPattern.computeIfAbsent(pattern, ignored -> new State());
    }

    private static int coverageTicks(long copies, long scaledRate) {
        long scaledCopies = multiplySaturated(copies, RATE_SCALE);
        long rawTicks = scaledCopies <= 0L
                ? 1L
                : 1L + (scaledCopies - 1L) / Math.max(MIN_RATE, scaledRate);
        long ticks = rawTicks <= 1L
                ? 1L
                : Math.max(
                        1L,
                        ceilingDivide(
                                multiplySaturated(
                                        rawTicks, REFILL_SAFETY_NUMERATOR),
                                REFILL_SAFETY_DENOMINATOR));
        return (int) Math.clamp(ticks, 1L, MAX_COVERAGE_TICKS);
    }

    private static long scaledRate(long copies, long ticks) {
        if (copies <= 0L) {
            return MIN_RATE;
        }
        return Math.clamp(
                multiplySaturated(copies, RATE_SCALE) / Math.max(1L, ticks),
                MIN_RATE,
                MAX_RATE);
    }

    private static long doubled(long rate) {
        return rate >= MAX_RATE / 2L ? MAX_RATE : rate * 2L;
    }

    private static long multiplySaturated(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right
                ? Long.MAX_VALUE
                : left * right;
    }

    private static long ceilingDivide(long amount, long divisor) {
        return amount <= 0L
                ? 0L
                : 1L + (amount - 1L) / divisor;
    }

    private static final class State {
        private long rate = RATE_SCALE;
        private long lastSuccessTick = Long.MIN_VALUE;
        private long lastOwnedCopies;
        private int cleanSuccesses;
        private boolean rejectedSinceLastSuccess;

        private void expireIfIdle(long gameTick) {
            if (lastSuccessTick == Long.MIN_VALUE) {
                return;
            }
            if (gameTick < lastSuccessTick
                    || gameTick - lastSuccessTick >= HISTORY_TTL) {
                rate = RATE_SCALE;
                lastSuccessTick = Long.MIN_VALUE;
                lastOwnedCopies = 0L;
                cleanSuccesses = 0;
                rejectedSinceLastSuccess = false;
            }
        }
    }
}
