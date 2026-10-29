package com.ezra.pricepulse.alert;

import static org.assertj.core.api.Assertions.assertThat;
import com.ezra.pricepulse.alert.RuleTransitionEngine.*;
import com.ezra.pricepulse.matching.RuleIndexEntry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RuleTransitionEngineTest {
    @ParameterizedTest
    @CsvSource({
        "ABOVE,ACTIVE,99,100,TRIGGERED,COOLDOWN", "ABOVE,ACTIVE,100,101,NONE,ACTIVE",
        "ABOVE,ACTIVE,99,99,NONE,ACTIVE", "BELOW,ACTIVE,101,100,TRIGGERED,COOLDOWN",
        "BELOW,ACTIVE,100,99,NONE,ACTIVE", "ABOVE,COOLDOWN,101,95,REARMED,ACTIVE",
        "ABOVE,COOLDOWN,101,96,NONE,COOLDOWN", "BELOW,COOLDOWN,99,105,REARMED,ACTIVE",
        "BELOW,COOLDOWN,99,104,NONE,COOLDOWN", "ABOVE,PAUSED,99,101,NONE,PAUSED",
        "ABOVE,COMPLETED,99,101,NONE,COMPLETED", "ABOVE,DELETED,99,101,NONE,DELETED"
    })
    void sharedBoundaries(String direction, String state, String previous, String current, String kind, String target) {
        var d = new Definition(AlertDirection.valueOf(direction), new BigDecimal("100"), AlertTriggerMode.REPEAT,
                new BigDecimal(direction.equals("ABOVE") ? "95" : "105"));
        var result = RuleTransitionEngine.evaluate(d, AlertRuleStatus.valueOf(state),
                new BigDecimal(previous), new BigDecimal(current));
        assertThat(result.kind().name()).isEqualTo(kind);
        assertThat(result.target().name()).isEqualTo(target);
    }

    @Test void onceCompletesAndBaselineDoesNotTrigger() {
        var d = new Definition(AlertDirection.ABOVE, new BigDecimal("100"), AlertTriggerMode.ONCE, null);
        assertThat(RuleTransitionEngine.evaluate(d, AlertRuleStatus.ACTIVE, null, new BigDecimal("101")).kind())
                .isEqualTo(Kind.NONE);
        assertThat(RuleTransitionEngine.evaluate(d, AlertRuleStatus.ACTIVE,
                new BigDecimal("99"), new BigDecimal("100")).target()).isEqualTo(AlertRuleStatus.COMPLETED);
        assertThat(new RuleIndexEntry(1, d, AlertRuleStatus.COMPLETED).score()).isNull();
    }
}
