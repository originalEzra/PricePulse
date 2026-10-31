package com.ezra.pricepulse.outbox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutboxWriterTest {
    @Test void emptyAndSingleAlertsKeepTheOriginalContract() {
        var mapper=mock(OutboxMapper.class);var writer=new OutboxWriter(mapper,new ObjectMapper());
        writer.alertTriggeredBatch(List.of());verifyNoInteractions(mapper);
        when(mapper.insertAlertTriggered(eq(1L),anyString())).thenReturn(1);
        writer.alertTriggeredBatch(List.of(new OutboxWriter.PendingAlert(1,2,3,4)));
        verify(mapper).insertAlertTriggered(eq(1L),anyString());verify(mapper,never()).insertAlertTriggeredBatch(anyList());
    }
    @Test void multipleAlertsAreBoundedOrderedAndRetainTheExistingPayload()throws Exception {
        var mapper=mock(OutboxMapper.class);var json=new ObjectMapper();var writer=new OutboxWriter(mapper,json);
        List<List<OutboxWriter.AlertPayload>> pages=new ArrayList<>();
        when(mapper.insertAlertTriggeredBatch(anyList())).thenAnswer(call->{List<OutboxWriter.AlertPayload> page=call.getArgument(0);pages.add(List.copyOf(page));return page.size();});
        var alerts=new ArrayList<OutboxWriter.PendingAlert>();for(int n=1;n<=1001;n++)alerts.add(new OutboxWriter.PendingAlert(n,2,3,4));
        writer.alertTriggeredBatch(alerts);
        assertThat(pages).extracting(List::size).containsExactly(500,500,1);
        for(var page:pages)for(int n=0;n<page.size();n++){
            var row=page.get(n);assertThat(row.position()).isEqualTo(n);
            assertThat(json.readTree(row.payload())).isEqualTo(json.readTree(json.writeValueAsString(Map.of("schemaVersion",1,"triggerId",row.triggerId(),"userId",2,"ruleId",3,"stockId",4))));
        }
        verify(mapper,never()).insertAlertTriggered(anyLong(),anyString());
    }
    @Test void missingOrLegacyRowsCannotBeSilentlyAccepted() {
        var mapper=mock(OutboxMapper.class);when(mapper.insertAlertTriggeredBatch(anyList())).thenReturn(1);
        var writer=new OutboxWriter(mapper,new ObjectMapper());
        assertThatThrownBy(()->writer.alertTriggeredBatch(List.of(new OutboxWriter.PendingAlert(1,2,3,4),new OutboxWriter.PendingAlert(2,2,3,4))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("every Outbox row");
    }
}
