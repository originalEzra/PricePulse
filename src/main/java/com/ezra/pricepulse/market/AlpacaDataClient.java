package com.ezra.pricepulse.market;

import com.ezra.pricepulse.common.PriceTicks;
import com.ezra.pricepulse.market.MarketDataModels.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Read-only market-data client. No account, order, subscription-purchase or trading endpoints. */
public final class AlpacaDataClient {
    @FunctionalInterface public interface Transport { JsonNode get(URI uri) throws Exception; }
    public record Download(List<Session> sessions, List<Bar> bars, int pages) {}
    private final Transport transport;
    public AlpacaDataClient(Transport transport) { this.transport = transport; }
    public static AlpacaDataClient authenticated(AlpacaProperties properties, ObjectMapper json) {
        properties.validate();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new AlpacaDataClient(uri -> {
            if (!Set.of("data.alpaca.markets","paper-api.alpaca.markets","api.alpaca.markets").contains(uri.getHost())
                    || !uri.getScheme().equals("https")) throw new IllegalArgumentException("Untrusted market-data endpoint");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                    .header("APCA-API-KEY-ID", properties.getKeyId())
                    .header("APCA-API-SECRET-KEY", properties.getSecretKey()).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // Provider error bodies may contain sensitive diagnostics. Never propagate them.
            if (response.statusCode() != 200) throw new IllegalStateException("Alpaca market data HTTP " + response.statusCode());
            return json.readTree(response.body());
        });
    }
    public Download download(AlpacaProperties properties, Map<String,Long> stocks, Instant from, Instant to) throws Exception {
        if (stocks.isEmpty() || stocks.size()>MarketUniverse.MAX_SYMBOLS || !to.isAfter(from)
                || Duration.between(from,to).compareTo(Duration.ofDays(45))>0)
            throw new IllegalArgumentException("History cache requires 1–15 symbols and at most 45 days");
        // Provider pagination counts extended-hours rows too, before we retain only regular minutes.
        // Scale the safety bound with the requested universe/range instead of truncating fifteen stocks at twenty pages.
        long calendarDays=Duration.between(from,to).toDays()+2;
        long maxPages=Math.max(20,(calendarDays*1440*stocks.size()+9999)/10000+1);
        var start = from.atZone(MarketTime.NEW_YORK).toLocalDate().minusDays(8);
        var end = to.atZone(MarketTime.NEW_YORK).toLocalDate().plusDays(40);
        String host = properties.getEnvironment().equals("paper") ? "paper-api.alpaca.markets" : "api.alpaca.markets";
        JsonNode calendar = transport.get(URI.create("https://"+host+"/v2/calendar?start="+start+"&end="+end));
        if (!calendar.isArray()) throw new IllegalStateException("Invalid market calendar");
        List<Session> sessions = new ArrayList<>();
        for (JsonNode day : calendar) {
            LocalDate date = LocalDate.parse(day.required("date").asText());
            Instant open = ZonedDateTime.of(date, LocalTime.parse(day.required("open").asText()),MarketTime.NEW_YORK).toInstant();
            Instant close = ZonedDateTime.of(date,LocalTime.parse(day.required("close").asText()),MarketTime.NEW_YORK).toInstant();
            if (!close.isAfter(open)) throw new IllegalStateException("Invalid market session");
            sessions.add(new Session(date,MarketTime.utc(open),MarketTime.utc(close)));
        }
        sessions.sort(Comparator.comparing(Session::sessionDate));
        Map<LocalDate,Session> byDate = new HashMap<>();
        sessions.forEach(s -> byDate.put(s.sessionDate(),s));
        List<Bar> bars = new ArrayList<>();
        String token = null;
        Set<String> tokens = new HashSet<>();
        int pages = 0;
        do {
            String query = "symbols="+encode(String.join(",",new TreeSet<>(stocks.keySet())))
                    +"&timeframe=1Min&feed=iex&adjustment=raw&limit=10000&start="+encode(from.toString())+"&end="+encode(to.toString())
                    +(token == null ? "" : "&page_token="+encode(token));
            JsonNode body = transport.get(URI.create("https://data.alpaca.markets/v2/stocks/bars?"+query));
            if (++pages > maxPages) throw new IllegalStateException("History pagination exceeded bounded cache range");
            JsonNode entries = body.required("bars");
            if (!entries.isObject()) throw new IllegalStateException("Invalid history response");
            Iterator<String> names = entries.fieldNames();
            while (names.hasNext()) {
                String symbol = names.next();
                Long stockId = stocks.get(symbol);
                if (stockId == null) throw new IllegalStateException("Unexpected history symbol");
                for (JsonNode item : entries.get(symbol)) {
                    Instant at = Instant.parse(item.required("t").asText()), available = at.plusSeconds(60);
                    Session session = byDate.get(at.atZone(MarketTime.NEW_YORK).toLocalDate());
                    // A minute's close is unavailable at its start; exclude extended-hours and unclosed bars.
                    if (session == null || !session.contains(at) || available.isAfter(session.close())
                            || available.isAfter(to) || at.isBefore(from)) continue;
                    bars.add(new Bar(null,stockId,MarketTime.utc(at),MarketTime.utc(available),
                            price(item,"o"),price(item,"h"),price(item,"l"),price(item,"c"),1,MarketTime.utc(to)));
                }
            }
            token = body.path("next_page_token").isNull() || body.path("next_page_token").isMissingNode()
                    ? null : body.get("next_page_token").asText();
            if (token != null && (token.isBlank() || !tokens.add(token))) throw new IllegalStateException("Repeated history page token");
        } while (token != null);
        return new Download(List.copyOf(sessions),List.copyOf(bars),pages);
    }
    private static java.math.BigDecimal price(JsonNode node, String key) {
        if (!node.required(key).isNumber()) throw new IllegalStateException("Invalid bar price");
        return PriceTicks.normalize(node.get(key).decimalValue(),"bar price");
    }
    private static String encode(String s) { return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8); }
}
