package com.jujin.freeway.flow.internal;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Stepper iterator (range: [start, end), step: step)
 *
 * @author noear 2025/10/19 created
 * @since 3.6
 */
public class Stepper implements Iterator<Integer> {

    /**
     * Parses a string and creates a stepper
     *
     * @param str supports two formats:
     *            1. "start...end" (step defaults to 1, e.g. "1...9")
     *            2. "start:end:step" (explicit step, e.g. "1:10:2")
     */
    public static Stepper from(String str) {
        int ellipsisIdx = str.indexOf("...");

        if (ellipsisIdx > 0) {
            String startStr = str.substring(0, ellipsisIdx);
            String endStr = str.substring(ellipsisIdx + 3);

            try {
                int start = Integer.parseInt(startStr);
                int end = Integer.parseInt(endStr);
                return new Stepper(start, end, 1);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Stepper parameters must be valid integers: " + str, e);
            }
        } else {
            String[] terms = str.split(":", 3);

            if (terms.length != 3) {
                throw new IllegalArgumentException("The stepper style must be 'start...end' or 'start:end:step'");
            }

            try {
                int start = Integer.parseInt(terms[0]);
                int end = Integer.parseInt(terms[1]);
                int step = Integer.parseInt(terms[2]);
                return new Stepper(start, end, step);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Stepper parameters must be valid integers: " + str, e);
            }
        }
    }

    private final int start;
    private final int end;
    private final int step;
    private int nextValue;
    private boolean hasMore;

    public Stepper(int start, int end, int step) {
        if (step <= 0) {
            throw new IllegalArgumentException("Step must be positive");
        }

        this.start = start;
        this.end = end;
        this.step = step;
        this.nextValue = start;
        this.hasMore = start < end;
    }

    @Override
    public boolean hasNext() {
        return hasMore;
    }

    @Override
    public Integer next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more elements in stepper");
        }

        int result = nextValue;

        if (nextValue < end - step) {
            nextValue += step;
        } else {
            hasMore = false;
        }

        return result;
    }

    @Override
    public String toString() {
        return "Stepper{" +
                "start=" + start +
                ", end=" + end +
                ", step=" + step +
                '}';
    }
}
