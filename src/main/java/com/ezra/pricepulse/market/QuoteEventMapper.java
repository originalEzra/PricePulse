package com.ezra.pricepulse.market;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuoteEventMapper {
    int insert(@Param("event") QuoteEvent event);

    QuoteEvent findByEventKey(String eventKey);

    @org.apache.ibatis.annotations.Select("""
        SELECT q.* FROM quote_events q JOIN market_stream_state s ON s.stock_id=q.stock_id
          AND s.accepted_sequence=q.accepted_sequence AND s.event_time_ns=q.event_time_ns
        WHERE q.stock_id=#{stockId} AND q.origin_type='REAL' AND q.processing_status='PROCESSED'
          AND q.provider='ALPACA' AND q.feed='iex' AND s.provider='ALPACA' AND s.feed='iex'
        ORDER BY q.id DESC LIMIT 1
        """)
    QuoteEvent latestAcceptedReal(long stockId);

    int markProcessed(long eventId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE quote_events SET accepted_sequence=#{acceptedSequence},processed_at=#{processedAt},
          baseline_reason=#{baselineReason},processing_status='PROCESSED',discard_reason=NULL
        WHERE id=#{id} AND processing_status='RECEIVED'
        """)
    int complete(QuoteEvent event);

    int markDiscarded(@Param("eventId") long eventId, @Param("reason") String reason);

    List<QuoteEvent> listByStock(@Param("stockId") long stockId, @Param("limit") int limit);
}
