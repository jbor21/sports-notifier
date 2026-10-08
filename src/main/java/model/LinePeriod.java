package model;

/** One column of a line score: the points each side scored in one period, inning, or overtime. */
public record LinePeriod(int period, int home, int away) {}
