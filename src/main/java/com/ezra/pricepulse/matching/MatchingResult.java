package com.ezra.pricepulse.matching;

public record MatchingResult(int candidates, int triggered, int rearmed) {
    public static MatchingResult empty() { return new MatchingResult(0, 0, 0); }
}
