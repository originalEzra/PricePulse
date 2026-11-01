package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuoteTimingTest {
    @Test void unboundHookIsInactiveAndNestedScopesRestoreTheOriginalSink()throws Exception {
        assertThat(QuoteTiming.start()).isZero();List<String> first=new ArrayList<>(),second=new ArrayList<>();
        try(var outer=QuoteTiming.bind((phase,n)->first.add(phase))){
            QuoteTiming.finish("first",QuoteTiming.start());
            try(var inner=QuoteTiming.bind((phase,n)->second.add(phase))){QuoteTiming.finish("inner",QuoteTiming.start());}
            QuoteTiming.finish("restored",QuoteTiming.start());
        }
        assertThat(first).containsExactly("first","restored");assertThat(second).containsExactly("inner");assertThat(QuoteTiming.start()).isZero();
    }
    @Test void failedMetricSinkCannotFailTheBusinessOperation()throws Exception {
        try(var scope=QuoteTiming.bind((phase,n)->{throw new IllegalStateException("metric failure");})){
            assertThatCode(()->QuoteTiming.finish("decision",QuoteTiming.start())).doesNotThrowAnyException();
        }
    }
}
