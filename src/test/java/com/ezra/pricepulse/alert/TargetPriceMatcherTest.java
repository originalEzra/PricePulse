package com.ezra.pricepulse.alert;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.matching.RuleIndexEntry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TargetPriceMatcherTest {
    @ParameterizedTest @CsvSource({"ABOVE,205,210,true","ABOVE,205,205,true","ABOVE,205,200,false","BELOW,205,200,true","BELOW,205,205,true","BELOW,205,210,false"})
    void realAndSimulationShareInclusiveTargetPredicate(String direction,String threshold,String current,boolean result){
        var d=new TargetPriceMatcher.Definition(AlertDirection.valueOf(direction),new BigDecimal(threshold));
        assertThat(TargetPriceMatcher.matches(d,AlertRuleStatus.ACTIVE,new BigDecimal(current))).isEqualTo(result);
        assertThat(TargetPriceMatcher.matches(d,AlertRuleStatus.COMPLETED,new BigDecimal(current))).isFalse();
        assertThat(TargetPriceMatcher.matches(d,AlertRuleStatus.DELETED,new BigDecimal(current))).isFalse();
    }
    @Test void onlyActiveTargetsAreIndexedAndBothBandsAreExact(){
        var d=new TargetPriceMatcher.Definition(AlertDirection.BELOW,PriceTicks.MAX_PRICE);
        long score=new RuleIndexEntry(1,d,AlertRuleStatus.ACTIVE).score();
        assertThat(score).isEqualTo(1_999_999_999_999_999L).isLessThan(1L<<53);assertThat((long)(double)score).isEqualTo(score);
        assertThat(new RuleIndexEntry(1,d,AlertRuleStatus.COMPLETED).score()).isNull();assertThat(new RuleIndexEntry(1,null,AlertRuleStatus.DELETED).score()).isNull();
    }
}
