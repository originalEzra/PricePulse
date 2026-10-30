package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimulationGeneratorTest {
    final BigDecimal price=new BigDecimal("100.1234"),ratio=new BigDecimal("0.003");
    List<Instant> times(int first,int size){return java.util.stream.IntStream.range(first,first+size).mapToObj(n->Instant.parse("2026-10-28T13:30:00.123456789Z").plusSeconds(n*60L)).toList();}
    @Test void seededPathIsIdenticalAfterBatchSplitAndFreshGeneratorInvocation() {
        for(var mode:SimulationGenerator.Mode.values()) {
            if(mode==SimulationGenerator.Mode.OPENING_GAP)continue; // Discrete opening is prepared outside the minute generator.
            var whole=SimulationGenerator.generate(SimulationGenerator.VERSION,42,1,price,mode,ratio,times(1,120));
            var first=SimulationGenerator.generate(SimulationGenerator.VERSION,42,1,price,mode,ratio,times(1,60));
            var second=SimulationGenerator.generate(SimulationGenerator.VERSION,42,61,first.getLast().price(),mode,ratio,times(61,60));
            var joined=new ArrayList<>(first);joined.addAll(second);assertThat(joined).isEqualTo(whole);
            assertThat(first).isEqualTo(SimulationGenerator.generate(SimulationGenerator.VERSION,42,1,price,mode,ratio,times(1,60)));
            assertThat(first).isNotEqualTo(SimulationGenerator.generate(SimulationGenerator.VERSION,43,1,price,mode,ratio,times(1,60)));
        }
    }
    @Test void directionalModesAndFourDecimalPricesRespectDocumentedPolicy() {
        var rise=SimulationGenerator.generate(SimulationGenerator.VERSION,1,1,price,SimulationGenerator.Mode.RISE,ratio,times(1,60));
        var fall=SimulationGenerator.generate(SimulationGenerator.VERSION,1,1,price,SimulationGenerator.Mode.FALL,ratio,times(1,60));
        assertThat(rise).extracting(p->p.price()).isSorted();
        assertThat(fall).extracting(p->p.price()).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(rise).allSatisfy(p->assertThat(p.price().scale()).isEqualTo(4));
        assertThat(SimulationGenerator.generate(SimulationGenerator.VERSION,1,1,new BigDecimal("0.0001"),SimulationGenerator.Mode.FALL,ratio,times(1,60)))
                .allSatisfy(p->assertThat(p.price()).isEqualByComparingTo("0.0001"));
    }
    @Test void rejectsUnknownVersionsAndInvalidAmplitude() {
        assertThatThrownBy(()->SimulationGenerator.generate("new-unknown-version",1,1,price,SimulationGenerator.Mode.NORMAL,ratio,times(1,1))).hasMessageContaining("unsupported");
        assertThatThrownBy(()->SimulationGenerator.generate(SimulationGenerator.VERSION,1,1,price,SimulationGenerator.Mode.NORMAL,new BigDecimal("0.3"),times(1,1))).hasMessageContaining("parameters");
    }
    @Test void discreteOpeningCannotBeGeneratedAsOrdinaryMinutePrices() {
        for(String version:List.of(SimulationGenerator.VERSION,SimulationGenerator.GAP_VERSION))
            assertThatThrownBy(()->SimulationGenerator.generate(version,42,1,price,SimulationGenerator.Mode.OPENING_GAP,ratio,times(1,60)))
                    .hasMessageContaining("parameters");
    }
}
