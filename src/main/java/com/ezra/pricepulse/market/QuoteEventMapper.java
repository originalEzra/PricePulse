package com.ezra.pricepulse.market;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuoteEventMapper {
    int insert(@Param("event") QuoteEvent event);

    QuoteEvent findByEventKey(String eventKey);

    int markProcessed(long eventId);

    int markDiscarded(@Param("eventId") long eventId, @Param("reason") String reason);

    List<QuoteEvent> listByStock(@Param("stockId") long stockId, @Param("limit") int limit);
}
