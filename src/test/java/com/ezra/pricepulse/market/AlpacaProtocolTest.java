package com.ezra.pricepulse.market;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AlpacaProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant receivedAt = Instant.parse("2026-10-29T14:09:31Z");

    @Test void nyseRegularSalesFromTheLiveAcceptanceRetainIdentityPriceAndNanoseconds() throws Exception {
        var ba = AlpacaProtocol.trade(json.readTree("""
                {"T":"t","S":"BA","c":[" "],"i":52983579171409,"p":170.105,"s":120,
                 "t":"2026-10-29T14:09:28.694415532Z","x":"V","z":"A"}
                """), receivedAt, Set.of("BA", "ORCL"));
        var orcl = AlpacaProtocol.trade(json.readTree("""
                {"T":"t","S":"ORCL","c":[" "],"i":52983579172409,"p":138.9,"s":100,
                 "t":"2026-10-29T14:09:29.300493151Z","x":"V","z":"A"}
                """), receivedAt, Set.of("BA", "ORCL"));
        assertThat(ba).isNotNull();
        assertThat(orcl).isNotNull();
        assertThat(ba.price()).isEqualByComparingTo("170.1050");
        assertThat(ba.eventTime()).isEqualTo(Instant.parse("2026-10-29T14:09:28.694415532Z"));
        assertThat(orcl.eventTime().getNano()).isEqualTo(300493151);
        assertThat(ba.eventKey()).isNotEqualTo(orcl.eventKey());
    }

    @ParameterizedTest @ValueSource(strings = {"A", "B"})
    void ctaRegularSalesAndSweepCombinationsAreEligible(String tape) throws Exception {
        assertThat(parse(tape, "[\" \"]")).isNotNull();
        assertThat(parse(tape, "[\" \",\"F\"]")).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings = {"A", "B"})
    void ctaRegularSalesDoNotLetExcludedConditionsThrough(String tape) throws Exception {
        for (String excluded : Set.of("I", "T", "W", "B", "Z", "7", "unknown")) {
            assertThat(parse(tape, "[\" \",\"" + excluded + "\"]")).isNull();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"C", "O", "", "unknown"})
    void aSpaceIsNotAGenericWildcardRegularCondition(String tape) throws Exception {
        assertThat(parse(tape, "[\" \"]")).isNull();
    }

    @Test void nasdaqRegularSalesSweepsAndOddLotExclusionKeepTheirExistingBehavior() throws Exception {
        assertThat(parse("C", "[\"@\"]")).isNotNull();
        assertThat(parse("C", "[\"@\",\"F\"]")).isNotNull();
        assertThat(parse("C", "[\"F\"]")).isNotNull();
        assertThat(parse("C", "[\"@\",\"I\"]")).isNull();
    }

    private AlpacaProtocol.Trade parse(String tape, String conditions) throws Exception {
        JsonNode frame = json.readTree("{\"T\":\"t\",\"S\":\"BA\",\"x\":\"V\",\"i\":1,\"p\":170.105,"
                + "\"s\":100,\"t\":\"2026-10-29T14:09:28.694415532Z\",\"z\":\"" + tape
                + "\",\"c\":" + conditions + "}");
        return AlpacaProtocol.trade(frame, receivedAt, Set.of("BA"));
    }
}
