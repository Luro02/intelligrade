/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.utils;

public class RequestCounter {
    private int current;

    public int next() {
        return ++this.current;
    }

    public boolean isCurrent(int request) {
        return this.current == request;
    }
}
