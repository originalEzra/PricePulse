package com.ezra.pricepulse.alert;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class OnceOnlyContractTest {
    @ParameterizedTest @ValueSource(strings={"\"triggerMode\":\"REPEAT\"","\"triggerMode\":\"ONCE\"","\"rearmPrice\":95"})
    void retiredInputsAreRejectedEvenWhenUnknownJsonIsGenerallyIgnored(String retired)throws Exception{
        var json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(()->json.readValue("{\"stockId\":1,\"name\":\"Target\",\"direction\":\"ABOVE\",\"thresholdPrice\":100,"+retired+"}",SaveAlertRuleRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void disablingFlywayCannotSilentlyInterpretOldRules(){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(2,1);
        assertThatThrownBy(()->new OnceOnlyModelGuard(jdbc)).isInstanceOf(IllegalStateException.class).hasMessageContaining("V12");
    }
}
