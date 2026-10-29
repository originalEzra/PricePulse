package com.ezra.pricepulse.simulation;

import com.ezra.pricepulse.common.error.*;
import java.time.*;
import java.util.*;

/** Frozen provider sessions, never a guessed weekday calendar. Minute endpoints may equal a close. */
public final class SimulationCalendar {
    private SimulationCalendar() {}
    public record Session(String date,Instant open,Instant close) {}
    public record Snapshot(String version,List<Session> sessions) {}
    public static List<Instant> minutes(Instant origin,int count,Snapshot calendar) {
        if(count<1 || count>10000 || calendar==null || calendar.sessions()==null || calendar.sessions().isEmpty())throw unavailable();
        List<Session> sessions=calendar.sessions(); Instant previous=null;
        for(Session s:sessions) {
            if(s.open()==null || s.close()==null || !s.close().isAfter(s.open()) || (previous!=null && s.open().isBefore(previous)))throw unavailable();
            previous=s.close();
        }
        if(origin.isBefore(sessions.getFirst().open()))throw unavailable();
        var points=new ArrayList<Instant>();Instant cursor=origin;int index=0;
        for(int point=0;point<count;point++) {
            long remaining=60_000_000_000L;
            while(remaining>0) {
                while(index<sessions.size() && !cursor.isBefore(sessions.get(index).close()))index++;
                if(index==sessions.size())throw unavailable();
                var session=sessions.get(index);
                if(cursor.isBefore(session.open()))cursor=session.open();
                long available=Duration.between(cursor,session.close()).toNanos();
                long consumed=Math.min(remaining,available);
                cursor=cursor.plusNanos(consumed);remaining-=consumed;
            }
            points.add(cursor);
        }
        return List.copyOf(points);
    }
    private static BusinessException unavailable(){return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"Frozen trading calendar cannot cover the next trading hour");}
}
