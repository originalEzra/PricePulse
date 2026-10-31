package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ezra.pricepulse.alert.AlertEvaluationService;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.matching.MatchingResult;
import com.ezra.pricepulse.stock.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;

class QuoteIngestionDedupTest {
    static final Instant NOW=Instant.parse("2026-10-30T14:00:00Z");
    final QuoteEventMapper events=mock(QuoteEventMapper.class);
    final StockMapper stocks=mock(StockMapper.class);
    final MarketDataMapper data=mock(MarketDataMapper.class);
    final AlertEvaluationService evaluation=mock(AlertEvaluationService.class);
    final QuoteCandidate input=new QuoteCandidate("identity",1,new BigDecimal("100"),MarketTime.utc(NOW),"TEST",7L);
    final QuoteIngestionService service=new QuoteIngestionService(events,stocks,
            new MarketProperties(Duration.ofMinutes(2),Duration.ofSeconds(30)),Clock.fixed(NOW,ZoneOffset.UTC),evaluation,data);
    @BeforeEach void stock(){when(stocks.findActiveByIdForUpdate(1)).thenReturn(new Stock(1,"PPTEST","Test","US","USD",null,null,"ACTIVE",null,null,"token"));}
    QuoteEvent stored(){var e=new QuoteEvent();e.setId(42L);e.setStockId(1L);e.setEventKey("identity");e.setOriginType("REAL");
        e.setPrice(input.price());e.setEventTime(input.eventTime());e.setEventTimeNs(MarketTime.nanos(NOW));
        e.setSource("TEST");e.setSequenceNo(7L);e.setProvider("INTERNAL");e.setFeed("fixture");return e;}
    DuplicateKeyException duplicate(String key){return new DuplicateKeyException("translated",new SQLException("Duplicate entry 'identity' for key '"+key+"'","23000",1062));}
    @Test void freshTradeDoesNotReadByIdentityBeforeInserting(){
        doAnswer(call->{((QuoteEvent)call.getArgument(0)).setId(42L);return 1;}).when(events).insert(any());
        when(evaluation.evaluate(any(),eq(42L),any(),any(),eq(false),isNull())).thenReturn(MatchingResult.empty());
        when(stocks.updateLatestQuote(eq(1L),any(),any())).thenReturn(1);when(events.complete(any())).thenReturn(1);
        assertThat(service.ingest(input,QuoteIngestionPolicy.LIVE).outcome()).isEqualTo(QuoteOutcome.PROCESSED);
        verify(events,never()).findByEventKey(any());
    }
    @ParameterizedTest @ValueSource(strings={"uk_quote_events_event_key","quote_events.uk_quote_events_event_key"})
    void expectedConstraintReadsAndChecksTheExistingPayload(String key){
        doThrow(duplicate(key)).when(events).insert(any());when(events.findByEventKey("identity")).thenReturn(stored());
        assertThat(service.ingest(input,QuoteIngestionPolicy.LIVE).outcome()).isEqualTo(QuoteOutcome.DUPLICATE);
        var order=inOrder(stocks,events);order.verify(stocks).findActiveByIdForUpdate(1);order.verify(events).insert(any());order.verify(events).findByEventKey("identity");
        verifyNoInteractions(evaluation,data);verify(stocks,never()).updateLatestQuote(anyLong(),any(),any());
    }
    @Test void conflictingPayloadStillFails(){
        doThrow(duplicate("quote_events.uk_quote_events_event_key")).when(events).insert(any());
        var old=stored();old.setPrice(new BigDecimal("101"));when(events.findByEventKey("identity")).thenReturn(old);
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verifyNoInteractions(evaluation,data);
    }
    @ParameterizedTest @ValueSource(strings={"PRIMARY","quote_events.other_unique","other_table.uk_quote_events_event_key"})
    void otherConstraintsAreNotHiddenEvenIfAMatchingEventCouldBeRead(String key){
        var failure=duplicate(key);doThrow(failure).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(failure);
        verify(events,never()).findByEventKey(any());
    }
    @Test void constraintTextInsideTheDuplicateValueCannotSpoofTheFinalKey(){
        var failure=new DuplicateKeyException("translated",new SQLException("Duplicate entry 'for key 'quote_events.uk_quote_events_event_key'' for key 'quote_events.other_unique'","23000",1062));
        doThrow(failure).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(failure);
        verify(events,never()).findByEventKey(any());
    }
    @Test void missingRowOrUnknownDriverFormatPropagatesTheOriginalException(){
        var failure=duplicate("quote_events.uk_quote_events_event_key");doThrow(failure).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(failure);
        reset(events);var unknown=new DuplicateKeyException("no vendor constraint detail");doThrow(unknown).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(unknown);
        verify(events,never()).findByEventKey(any());
    }
    @Test void deadlocksAndOtherDatabaseFailuresAreNotDuplicateResults(){
        var failure=new CannotAcquireLockException("deadlock",new SQLException("Deadlock found","40001",1213));
        doThrow(failure).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(failure);
        verify(events,never()).findByEventKey(any());
    }
    @Test void vendorCodeAndSqlStateMustBothMatch(){
        var failure=new DuplicateKeyException("translated",new SQLException("Duplicate entry 'x' for key 'quote_events.uk_quote_events_event_key'","40001",1062));
        doThrow(failure).when(events).insert(any());
        assertThatThrownBy(()->service.ingest(input,QuoteIngestionPolicy.LIVE)).isSameAs(failure);
        verify(events,never()).findByEventKey(any());
    }
}
