package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.common.error.*;
import com.ezra.pricepulse.simulation.SimulationModels.Point;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Versioned per-sequence randomness; persisted price rounding is part of the algorithm. */
public final class SimulationGenerator {
    public static final String VERSION="splitmix64-price-v1";
    public static final String GAP_VERSION="opening-gap-v1";
    public static boolean supports(String version){return VERSION.equals(version)||GAP_VERSION.equals(version);}
    public enum Mode { NORMAL, RISE, FALL, OSCILLATE, OPENING_GAP }
    private SimulationGenerator() {}
    public static List<Point> generate(String version,long seed,long firstSequence,BigDecimal previous,
            Mode mode,BigDecimal ratio,List<Instant> times) {
        if(!supports(version))throw new BusinessException(ErrorCode.CONFLICT,"Generator version is unsupported; saved results remain readable");
        if(firstSequence<1 || mode==null || mode==Mode.OPENING_GAP || ratio==null || ratio.signum()<=0 || ratio.compareTo(new BigDecimal("0.003"))>0)
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,"Invalid simulation parameters");
        var result=new ArrayList<Point>();BigDecimal price=PriceTicks.normalize(previous,"price");long sequence=firstSequence;
        for(Instant time:times) {
            long mixed=seed+sequence*0x9e3779b97f4a7c15L;
            mixed=(mixed^(mixed>>>30))*0xbf58476d1ce4e5b9L;
            mixed=(mixed^(mixed>>>27))*0x94d049bb133111ebL;
            mixed^=mixed>>>31;
            BigDecimal unit=BigDecimal.valueOf((mixed>>>11)*0x1.0p-53);
            BigDecimal factor=mode==Mode.NORMAL?unit.multiply(BigDecimal.valueOf(2)).subtract(BigDecimal.ONE)
                    :unit.add(BigDecimal.ONE).divide(BigDecimal.valueOf(2));
            if(mode==Mode.FALL || (mode==Mode.OSCILLATE && ((sequence-1)/5)%2==1))factor=factor.negate();
            price=price.multiply(BigDecimal.ONE.add(ratio.multiply(factor))).setScale(4,RoundingMode.HALF_UP)
                    .max(new BigDecimal("0.0001"));
            price=PriceTicks.normalize(price,"generated price");
            result.add(new Point(time,price));sequence=Math.addExact(sequence,1);
        }
        return List.copyOf(result);
    }
}
