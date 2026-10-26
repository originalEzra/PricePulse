package com.ezra.pricepulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("standalone")
class StandaloneApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void startsWithoutDatabaseRedisMqOrAiCredentials() throws Exception {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(RocketMQTemplate.class)).isEmpty();
        mvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("PricePulse"))
                .andExpect(jsonPath("$.data.phase").value("phase-5"))
                .andExpect(jsonPath("$.data.profiles[0]").value("standalone"))
                .andExpect(jsonPath("$.error").doesNotExist());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
