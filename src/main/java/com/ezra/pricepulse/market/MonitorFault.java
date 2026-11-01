package com.ezra.pricepulse.market;

import java.time.Instant;

/** Immutable callback facts. No database or provider I/O occurs while collecting them. */
public record MonitorFault(String id,String scope,Long stockId,String reason,Instant startedAt,
        Instant recoveredAt,long discarded,long revision,String continuity) {
    public String activeScope(){return recoveredAt==null?(stockId==null?"CONNECTION":"QUEUE:"+stockId):null;}
}
