package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.ezra.pricepulse.common.error.BusinessException;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MarketDataTest {
    private final ObjectMapper json=new ObjectMapper();
    private final List<Session> sessions=List.of(
        session("2026-10-23","13:30","20:00"),session("2026-10-26","13:30","20:00"),
        session("2026-10-27","13:30","20:00"),session("2026-10-28","13:30","20:00"),
        session("2026-10-30","13:30","20:00"),session("2026-11-02","14:30","21:00"),
        session("2026-11-27","14:30","18:00"));
    @Test void windowsRespectFullCurrentSessionAndElapsedTimeRatherThanTradingMinuteCompression() {
        Instant now=Instant.parse("2026-10-28T15:00:00Z");
        Range day=MarketWindows.resolve("1D",now,now,sessions);
        assertThat(day.from()).isEqualTo(Instant.parse("2026-10-28T13:30:00Z"));
        assertThat(day.to()).isEqualTo(Instant.parse("2026-10-28T20:00:00Z"));
        assertThat(day.asOf()).isEqualTo(now);
        assertThat(MarketWindows.resolve("1H",now,now,sessions).from()).isEqualTo(now.minusSeconds(3600));
        assertThat(Duration.between(MarketWindows.resolve("1W",now,now,sessions).from(),now)).isEqualTo(Duration.ofDays(7));
    }
    @Test void closedWeekendPremarketDstAndEarlyCloseUseVerifiedCalendar() {
        Instant weekend=Instant.parse("2026-10-25T14:00:00Z");
        assertThat(MarketWindows.resolve("1H",weekend,weekend,sessions).asOf()).isEqualTo(Instant.parse("2026-10-23T20:00:00Z"));
        Instant beforeOpen=Instant.parse("2026-10-28T12:00:00Z");
        assertThat(MarketWindows.resolve("1D",beforeOpen,beforeOpen,sessions).from()).isEqualTo(Instant.parse("2026-10-27T13:30:00Z"));
        Instant afterDst=Instant.parse("2026-11-02T15:00:00Z");
        assertThat(MarketWindows.resolve("1D",afterDst,afterDst,sessions).from()).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
        Instant earlyClose=Instant.parse("2026-11-27T20:00:00Z");
        assertThat(MarketWindows.resolve("1D",earlyClose,earlyClose,sessions).to()).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(MarketWindows.resolve("1M",afterDst,afterDst,sessions).from()).isEqualTo(Instant.parse("2026-10-02T14:00:00Z"));
        assertThat(MarketWindows.resolve("1W",afterDst,afterDst,sessions).from()).isEqualTo(Instant.parse("2026-10-26T14:00:00Z"));
    }
    @Test void neverInventsCalendarOrAcceptsFutureAnchors() {
        Instant now=Instant.parse("2026-10-28T15:00:00Z");
        assertThatThrownBy(()->MarketWindows.resolve("1D",now,now,List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->MarketWindows.resolve("1D",now.plusSeconds(1),now,sessions)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->MarketWindows.resolve("1Y",now,now,sessions)).isInstanceOf(BusinessException.class);
    }
    @Test void nanosecondIdentityDoesNotUseTradeIdAsEventOrderOrRoundToMicroseconds() throws Exception {
        var a=AlpacaProtocol.trade(trade("12","2026-10-28T14:00:00.123456789Z","100.1234","[\"@\"]"),Instant.now(),Set.of("AAPL"));
        var b=AlpacaProtocol.trade(trade("13","2026-10-28T14:00:00.123456789Z","101","[\"@\"]"),Instant.now(),Set.of("AAPL"));
        assertThat(a.eventKey()).isNotEqualTo(b.eventKey());
        assertThat(a.eventTime().getNano()).isEqualTo(123456789);
        assertThat(MarketTime.instant(MarketTime.nanos(a.eventTime()))).isEqualTo(a.eventTime());
        assertThat(AlpacaProtocol.trade(trade("12","2026-10-28T14:00:00.123456789Z","102","[\"@\"]"),Instant.now(),Set.of("AAPL")).eventKey()).isEqualTo(a.eventKey());
    }
    @Test void barsQuotesOddLotsAndUnsupportedPrecisionCannotEnterTradeMatching() throws Exception {
        assertThat(AlpacaProtocol.trade(json.readTree("{\"T\":\"q\"}"),Instant.now(),Set.of("AAPL"))).isNull();
        assertThat(AlpacaProtocol.trade(trade("1","2026-10-28T14:00:00Z","100","[\"@\",\"I\"]"),Instant.now(),Set.of("AAPL"))).isNull();
        assertThatThrownBy(()->AlpacaProtocol.trade(trade("1","2026-10-28T14:00:00Z","100.12345","[\"@\"]"),Instant.now(),Set.of("AAPL"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->AlpacaProtocol.trade(trade("1","2026-10-28T14:00:00Z","100","[\"@\"]"),Instant.now(),Set.of("MSFT"))).isInstanceOf(RuntimeException.class);
    }
    @Test void historicalPagesAreFollowedAndOnlyClosedRthBarsBecomePoints() throws Exception {
        var requests=new ArrayList<String>();
        var client=new AlpacaDataClient(uri->{
            requests.add(uri.toString());
            if(uri.getPath().endsWith("calendar"))return json.readTree("[{\"date\":\"2026-10-28\",\"open\":\"09:30\",\"close\":\"16:00\"}]");
            if(!uri.getQuery().contains("page_token"))return json.readTree("{\"bars\":{\"AAPL\":["+bar("2026-10-28T13:29:00Z")+","+bar("2026-10-28T13:30:00Z")+"]},\"next_page_token\":\"page2\"}");
            return json.readTree("{\"bars\":{\"MSFT\":["+bar("2026-10-28T13:31:00Z")+","+bar("2026-10-28T13:32:00Z")+"]},\"next_page_token\":null}");
        });
        var result=client.download(new AlpacaProperties(),Map.of("AAPL",1L,"MSFT",2L),Instant.parse("2026-10-28T00:00:00Z"),Instant.parse("2026-10-28T13:32:30Z"));
        assertThat(result.pages()).isEqualTo(2);assertThat(result.bars()).hasSize(2);
        assertThat(result.bars()).extracting(Bar::intervalEnd).containsExactly(LocalDateTime.parse("2026-10-28T13:31:00"),LocalDateTime.parse("2026-10-28T13:32:00"));
        assertThat(requests.getLast()).contains("feed=iex","adjustment=raw","page_token=page2");
    }
    @Test void repeatedPageTokenFailsInsteadOfPublishingPartialHistoryCoverage() throws Exception {
        var client=new AlpacaDataClient(uri->json.readTree(uri.getPath().endsWith("calendar")?"[]":"{\"bars\":{},\"next_page_token\":\"same\"}"));
        assertThatThrownBy(()->client.download(new AlpacaProperties(),Map.of("AAPL",1L),Instant.now().minusSeconds(60),Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Repeated");
    }
    @Test void signedPaginationBindsUserStockRangeAndWatermark() {
        var cursor=new TimelineCursor(new SecretKeySpec("test-secret-with-at-least-32-bytes".getBytes(),"HmacSHA256"));
        String value=cursor.encode(1,2,3,4,5,8);
        assertThat(cursor.decode(value,1,2,3,4)).isEqualTo(new TimelineCursor.Position(5,8));
        assertThatThrownBy(()->cursor.decode(value,2,2,3,4)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->cursor.decode(value,1,2,3,9)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->cursor.decode(value+"x",1,2,3,4)).isInstanceOf(BusinessException.class);
    }
    @Test void expectedClosedMinutesUseCalendarBoundariesAndPreciseInclusiveRangeRatherThanPointCount() {
        var calendar=List.of(session("2026-10-30","13:30","20:00"),session("2026-11-02","14:30","21:00"),
                session("2026-11-27","14:30","18:00"));
        assertThat(MarketTimelineService.expectedMinutes(calendar,calendar.getFirst().open(),calendar.getLast().close())).isEqualTo(390+390+210);
        var open=calendar.getFirst().open();
        assertThat(MarketTimelineService.expectedMinutes(calendar,open.plusSeconds(60),open.plusSeconds(120))).isEqualTo(2);
        assertThat(MarketTimelineService.expectedMinutes(calendar,open.plusSeconds(60).plusNanos(1),open.plusSeconds(120))).isEqualTo(1);
    }
    @Test void chartCalendarIncludesAdjacentSessionsButDoesNotGuessOutsideTheCache() {
        var all=sessions.stream().map(s->new TradingSession(s.sessionDate().toString(),s.open(),s.close())).toList();
        var clipped=TradingSession.forRange(all,Instant.parse("2026-10-31T00:00:00Z"),Instant.parse("2026-11-02T16:00:00Z"));
        assertThat(clipped).extracting(TradingSession::date).containsExactly("2026-10-30","2026-11-02","2026-11-27");
        assertThat(TradingSession.forRange(all,Instant.parse("2026-09-01T00:00:00Z"),Instant.parse("2026-09-02T00:00:00Z")))
                .extracting(TradingSession::date).containsExactly("2026-10-23");
    }
    @Test void observationIsDefaultAndCredentialObjectIsRedacted() {
        var p=new AlpacaProperties();p.setKeyId("private-key");p.setSecretKey("private-secret");
        assertThat(p.isEnabled()).isFalse();assertThat(p.isBusinessWritesEnabled()).isFalse();
        assertThat(p.toString()).doesNotContain("private-key","private-secret");
    }
    @Test void fifteenDistinctApprovedSymbolsAreSupportedButDuplicatesAndUnknownSymbolsAreRejected() {
        var p=new AlpacaProperties();p.setEnabled(true);p.setKeyId("dummy-key");p.setSecretKey("dummy-secret");
        assertThat(p.getSymbols()).hasSize(15).contains("AAPL","MSFT","NVDA","AMZN","GOOGL","TSLA","HOOD");
        assertThatCode(p::validate).doesNotThrowAnyException();
        p.setSymbols(List.of("AAPL","AAPL"));assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
        p.setSymbols(List.of("UNKNOWN"));assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
        assertThat(p.isBusinessWritesEnabled()).isFalse();
    }
    @Test void fifteenStockMonthFollowsPagesPastTheOldTwentyPageLimit() throws Exception {
        var requested=new java.util.concurrent.atomic.AtomicInteger();
        var stocks=new LinkedHashMap<String,Long>();MarketUniverse.SYMBOLS.forEach(symbol->stocks.put(symbol,(long)stocks.size()+1));
        var client=new AlpacaDataClient(uri->{
            if(uri.getPath().endsWith("calendar"))return json.readTree("[{\"date\":\"2026-10-28\",\"open\":\"09:30\",\"close\":\"16:00\"}]");
            int page=requested.incrementAndGet();
            return json.readTree(page<22?"{\"bars\":{},\"next_page_token\":\"page"+(page+1)+"\"}":
                    "{\"bars\":{\"HOOD\":["+bar("2026-10-28T13:30:00Z")+"]},\"next_page_token\":null}");
        });
        var result=client.download(new AlpacaProperties(),stocks,Instant.parse("2026-10-01T00:00:00Z"),Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(result.pages()).isEqualTo(22);assertThat(result.bars()).hasSize(1);
        assertThat(result.bars().getFirst().stockId()).isEqualTo(stocks.get("HOOD"));
    }
    @Test void historyRangeAndPageSafetyBoundsStillFailRatherThanReturnTruncatedCoverage() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var client=new AlpacaDataClient(uri->{
            if(uri.getPath().endsWith("calendar"))return json.readTree("[]");
            return json.readTree("{\"bars\":{},\"next_page_token\":\"p"+calls.incrementAndGet()+"\"}");
        });
        Instant to=Instant.parse("2026-10-29T00:00:00Z");
        assertThatThrownBy(()->client.download(new AlpacaProperties(),Map.of("AAPL",1L),to.minusSeconds(46L*86400),to))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(()->client.download(new AlpacaProperties(),Map.of("AAPL",1L),to.minusSeconds(60),to))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bounded cache range");
        assertThat(calls).hasValue(21);
    }
    private JsonNode trade(String id,String time,String price,String conditions)throws Exception {
        return json.readTree("{\"T\":\"t\",\"S\":\"AAPL\",\"x\":\"V\",\"i\":"+id+",\"p\":"+price+",\"s\":100,\"c\":"+conditions+",\"t\":\""+time+"\"}");
    }
    private String bar(String at){return "{\"t\":\""+at+"\",\"o\":100,\"h\":102,\"l\":99,\"c\":101}";}
    private static Session session(String date,String open,String close) {
        return new Session(LocalDate.parse(date),LocalDateTime.parse(date+"T"+open+":00"),LocalDateTime.parse(date+"T"+close+":00"));
    }
}
