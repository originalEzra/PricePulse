package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlpacaSubscriptionsTest {
    AlpacaSubscriptions ready(Map<String,Long> desired){var state=new AlpacaSubscriptions();state.desire(desired);state.connect();state.authenticated();return state;}
    @Test void commandsAreDeduplicatedSerializedAndOnlyAckedStocksAreConfirmed(){
        var state=ready(Map.of("AAPL",1L,"PLTR",1L));
        var first=state.next();assertThat(first.symbol()).isEqualTo("AAPL");assertThat(state.next()).isNull();
        assertThat(state.view().confirms("AAPL")).isFalse();state.acknowledge(Set.of("AAPL"));
        assertThat(state.view().confirms("AAPL")).isTrue();assertThat(state.next().symbol()).isEqualTo("PLTR");
        assertThat(state.view().confirms("PLTR")).isFalse();state.acknowledge(Set.of("AAPL","PLTR"));
        assertThat(state.next()).isNull();assertThat(state.view().confirms("PLTR")).isTrue();
    }
    @Test void cancelDuringSubscribeRetainsUncertaintyAndLateAckIsCompensatedByUnsubscribe(){
        var state=ready(Map.of("PLTR",1L));state.next();state.desire(Map.of());
        assertThat(state.view().absent("PLTR")).isFalse();assertThat(state.next()).isNull();
        state.acknowledge(Set.of("PLTR"));assertThat(state.view().confirms("PLTR")).isFalse();
        assertThat(state.next().action()).isEqualTo("unsubscribe");assertThat(state.view().absent("PLTR")).isFalse();
        state.acknowledge(Set.of());assertThat(state.view().absent("PLTR")).isTrue();
    }
    @Test void readdDuringUnsubscribeNeverUsesOldAckAsANewConfirmation(){
        var state=ready(Map.of("PLTR",1L));state.next();state.acknowledge(Set.of("PLTR"));
        state.desire(Map.of());state.next();state.desire(Map.of("PLTR",3L));
        assertThat(state.view().confirms("PLTR")).isFalse();state.acknowledge(Set.of());
        assertThat(state.next().intent()).isEqualTo(3);assertThat(state.view().confirms("PLTR")).isFalse();
        state.acknowledge(Set.of("PLTR"));assertThat(state.view().confirms("PLTR")).isTrue();
    }
    @Test void oldInFlightSubscribeAckCannotConfirmACancelledAndRecreatedIntent(){
        var state=ready(Map.of("PLTR",1L));state.next();state.desire(Map.of());state.desire(Map.of("PLTR",3L));
        state.acknowledge(Set.of("PLTR"));assertThat(state.view().confirms("PLTR")).isFalse();
        assertThat(state.next().action()).isEqualTo("unsubscribe");state.acknowledge(Set.of());
        assertThat(state.next().intent()).isEqualTo(3);state.acknowledge(Set.of("PLTR"));assertThat(state.view().confirms("PLTR")).isTrue();
    }
    @Test void explicitLimitFailurePreservesExistingSetAndRetryRequiresANewIntent(){
        var state=ready(Map.of("AAPL",1L));state.next();state.acknowledge(Set.of("AAPL"));
        state.desire(Map.of("AAPL",1L,"PLTR",1L));state.next();assertThat(state.reject(405)).isTrue();
        assertThat(state.view().confirms("AAPL")).isTrue();assertThat(state.view().failures()).containsKey("PLTR");assertThat(state.next()).isNull();
        state.desire(Map.of("AAPL",1L,"PLTR",2L));assertThat(state.next().intent()).isEqualTo(2);
        assertThat(state.reject(500)).isFalse();assertThat(state.view().pending()).isNotNull();
    }
    @Test void oldAndUnexpectedAcksCannotSettleTheCurrentCommand(){
        var state=ready(Map.of("AAPL",1L));state.next();state.acknowledge(Set.of("AAPL"));
        state.desire(Map.of("AAPL",1L,"SPY",2L));var current=state.next();
        assertThat(state.acknowledge(Set.of("AAPL"))).isFalse();assertThat(state.view().pending()).isEqualTo(current);
        assertThatThrownBy(()->state.acknowledge(Set.of("AAPL","WRONG"))).isInstanceOf(IllegalStateException.class);
        assertThat(state.view().actual()).containsExactly("AAPL");
    }
    @Test void disconnectInvalidatesAllProofAndReconnectUsesCurrentDemand(){
        var state=ready(Map.of("PLTR",1L));state.next();state.acknowledge(Set.of("PLTR"));String old=state.view().epoch();
        state.disconnect();assertThat(state.view().confirms("PLTR")).isFalse();assertThat(state.view().absent("OTHER")).isFalse();
        state.desire(Map.of("SPY",4L));state.connect();state.authenticated();
        assertThat(state.view().epoch()).isNotEqualTo(old);assertThat(state.next().symbol()).isEqualTo("SPY");
        assertThat(state.view().confirms("SPY")).isFalse();assertThat(state.view().absent("PLTR")).isTrue();
    }
    @Test void ambiguousFailureNeedsAFreshConnectionBeforeAbsenceIsProved(){
        var state=ready(Map.of("PLTR",1L));state.next();assertThat(state.rejectUncertain(500)).isTrue();
        assertThat(state.view().rejectedAbsent("PLTR")).isFalse();state.disconnect();assertThat(state.view().rejectedAbsent("PLTR")).isFalse();
        state.connect();state.authenticated();assertThat(state.view().rejectedAbsent("PLTR")).isTrue();assertThat(state.next()).isNull();
        state.desire(Map.of("PLTR",2L));assertThat(state.next().intent()).isEqualTo(2);
    }
    @Test void rejectionOfAnOlderIntentDoesNotBlockANewerRequest(){
        var state=ready(Map.of("PLTR",1L));state.next();state.desire(Map.of("PLTR",3L));state.reject(405);
        assertThat(state.next().intent()).isEqualTo(3);state.acknowledge(Set.of("PLTR"));assertThat(state.view().confirms("PLTR")).isTrue();
    }
}
