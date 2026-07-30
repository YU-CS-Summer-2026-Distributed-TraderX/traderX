package finos.traderx.ordermatcher.lmax;

/**
 * Minimal growable primitive int list for the BLP's per-security open-order index.
 * No boxing, no iterators; removal is unordered swap-with-last so scans stay O(1) per
 * removal (NGC-04). Growth only happens as a security's book first deepens — steady-state
 * operation allocates nothing.
 */
public final class IntList {
    private int[] values;
    private int size;

    public IntList(int initialCapacity) {
        values = new int[Math.max(4, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public int get(int index) {
        return values[index];
    }

    public void add(int value) {
        if (size == values.length) {
            int[] grown = new int[values.length * 2];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
        values[size++] = value;
    }

    /** Swap-with-last removal; the caller re-checks the same index after removal. */
    public void removeAtUnordered(int index) {
        values[index] = values[--size];
    }

    public boolean removeValueUnordered(int value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                removeAtUnordered(i);
                return true;
            }
        }
        return false;
    }
}
