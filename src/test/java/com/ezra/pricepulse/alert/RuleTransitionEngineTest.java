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

    @ParameterizedTest @CsvSource({"ABOVE,205,210,true","ABOVE,205,205,true","ABOVE,205,200,false","BELOW,205,200,true","BELOW,205,205,true","BELOW,205,210,false"})
    void realOnceReachesWithoutAPreviousPointButSimulationRetainsCrossing(String direction,String threshold,String price,boolean hit){
        var d=new Definition(AlertDirection.valueOf(direction),new BigDecimal(threshold),AlertTriggerMode.ONCE,null);
        var current=new BigDecimal(price);
        assertThat(RuleTransitionEngine.evaluateReal(d,AlertRuleStatus.ACTIVE,null,current).kind()).isEqualTo(hit?Kind.TRIGGERED:Kind.NONE);
        assertThat(RuleTransitionEngine.evaluateReal(d,AlertRuleStatus.ACTIVE,current,current).kind()).isEqualTo(hit?Kind.TRIGGERED:Kind.NONE);
        assertThat(RuleTransitionEngine.evaluate(d,AlertRuleStatus.ACTIVE,current,current).kind()).isEqualTo(Kind.NONE);
        assertThat(RuleTransitionEngine.evaluateReal(d,AlertRuleStatus.COMPLETED,null,current).kind()).isEqualTo(Kind.NONE);
    }

    @Test void realOnceBandIsExactAndSeparateFromRepeatAndSimulation(){
        var rule=new AlertRule();rule.setId(1L);rule.setDirection(AlertDirection.BELOW);rule.setTriggerMode(AlertTriggerMode.ONCE);
        rule.setThresholdPrice(com.ezra.pricepulse.common.PriceTicks.MAX_PRICE);rule.setStatus(AlertRuleStatus.ACTIVE);
        long score=RuleIndexEntry.from(rule).score();
        assertThat(score).isEqualTo(5_999_999_999_999_999L).isLessThan(1L<<53);
        assertThat((long)(double)score).isEqualTo(score);
        assertThat(new RuleIndexEntry(1,Definition.from(rule),rule.getStatus()).score()).isLessThan(2_000_000_000_000_000L);
    }
}
