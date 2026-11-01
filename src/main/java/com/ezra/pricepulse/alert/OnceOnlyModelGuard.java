package com.ezra.pricepulse.alert;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Also fail safely when an operator disables Flyway: old rows must never be read as once-only rules. */
@Component @Profile("!standalone & !mq-it") @DependsOnDatabaseInitialization
public class OnceOnlyModelGuard {
    public OnceOnlyModelGuard(JdbcTemplate jdbc){
        Integer legacy=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='alert_rules' AND column_name IN ('trigger_mode','rearm_price')",Integer.class);
        Integer table=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='alert_rules'",Integer.class);
        if(legacy==null||legacy!=0||table==null||table!=1)throw new IllegalStateException("The once-only rule model requires verified V12 schema; legacy rules cannot be interpreted automatically");
    }
}
