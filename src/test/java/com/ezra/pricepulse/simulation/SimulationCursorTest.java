package com.ezra.pricepulse.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SimulationCursorTest {
    final SimulationCursor cursor=new SimulationCursor(new SecretKeySpec("simulation-test-only-signature-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    @Test void bindsUserRunPurposeRangeAndImmutableWatermark() {
        String value=cursor.encode(1,"run-a","EVENTS",10,20,100,200);
        assertThat(cursor.decode(value,1,"run-a","EVENTS",10,20)).isEqualTo(new SimulationCursor.Position(100,200));
        assertThatThrownBy(()->cursor.decode(value,2,"run-a","EVENTS",10,20)).hasMessageContaining("cursor");
        assertThatThrownBy(()->cursor.decode(value,1,"run-b","EVENTS",10,20)).hasMessageContaining("cursor");
        assertThatThrownBy(()->cursor.decode(value,1,"run-a","POINTS",10,20)).hasMessageContaining("cursor");
        assertThatThrownBy(()->cursor.decode(value,1,"run-a","EVENTS",10,21)).hasMessageContaining("cursor");
        assertThatThrownBy(()->cursor.decode("x"+value,1,"run-a","EVENTS",10,20)).hasMessageContaining("cursor");
    }
}
