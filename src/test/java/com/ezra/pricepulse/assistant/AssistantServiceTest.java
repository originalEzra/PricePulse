package com.ezra.pricepulse.assistant;

import com.ezra.pricepulse.alert.AlertDirection;
import com.ezra.pricepulse.alert.AlertTriggerMode;
import com.ezra.pricepulse.common.error.BusinessException;
import com.ezra.pricepulse.common.error.ErrorCode;
import com.ezra.pricepulse.stock.Stock;
import com.ezra.pricepulse.stock.StockMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

class AssistantServiceTest {

    @Test
    void resolvesACompleteModelDraftToAnExistingStockWithoutCreatingARule() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "demo001", "", AlertDirection.ABOVE, "100", AlertTriggerMode.ONCE,
                "", "突破 100", "用户自然语言生成");
        StockMapper stockMapper = mock(StockMapper.class);
        when(stockMapper.findActiveBySymbol("DEMO001", null)).thenReturn(List.of(stock()));
        var service = new AssistantService(generator, stockMapper);

        AlertDraftResponse response = service.draft(new AlertDraftRequest("demo001 涨到 100 提醒我"));

        assertThat(response.ready()).isTrue();
        assertThat(response.clarificationQuestion()).isNull();
        assertThat(response.draft().stockId()).isEqualTo(1L);
        assertThat(response.draft().stockSymbol()).isEqualTo("DEMO001");
        assertThat(response.draft().thresholdPrice()).isEqualByComparingTo("100.0000");
        assertThat(response.draft().triggerMode()).isEqualTo(AlertTriggerMode.ONCE);
        verify(stockMapper, never()).findActiveByName(any(), any());
        verify(stockMapper, never()).findActiveByFragment(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"001", "Technology", "demo tech"})
    void resolvesAUniqueNameOrCodeFragment(String reference) {
        StockMapper mapper = mock(StockMapper.class);
        when(mapper.findActiveByFragment("%" + reference + "%", null)).thenReturn(List.of(stock()));

        var response = new AssistantService(text -> draftFor(reference, ""), mapper)
                .draft(new AlertDraftRequest(reference + " 涨到 100 提醒一次"));

        assertThat(response.ready()).isTrue();
        assertThat(response.draft().stockSymbol()).isEqualTo("DEMO001");
        assertThat(response.draft().stockName()).isEqualTo("Demo Technology");
    }

    @Test
    void prefersAnExactNameOverPartialMatches() {
        StockMapper mapper = mock(StockMapper.class);
        when(mapper.findActiveByName("Demo Technology", null)).thenReturn(List.of(stock()));

        var response = new AssistantService(text -> draftFor("Demo Technology", ""), mapper)
                .draft(new AlertDraftRequest("Demo Technology 涨到 100 提醒一次"));

        assertThat(response.ready()).isTrue();
        assertThat(response.draft().stockId()).isEqualTo(1L);
        verify(mapper, never()).findActiveByFragment(any(), any());
    }

    @Test
    void refusesAFullSymbolInventedByTheModelFromAPartialReference() {
        StockMapper mapper = mock(StockMapper.class);

        var response = new AssistantService(text -> draftFor("DEMO001", ""), mapper)
                .draft(new AlertDraftRequest("001 涨到 100 提醒一次"));

        assertThat(response.ready()).isFalse();
        assertThat(response.draft()).isNull();
        assertThat(response.clarificationQuestion()).contains("名称或代码片段");
        verifyNoInteractions(mapper);
    }

    @Test
    void listsCandidatesInsteadOfChoosingAnAmbiguousFragment() {
        StockMapper mapper = mock(StockMapper.class);
        when(mapper.findActiveByFragment("%001%", null)).thenReturn(List.of(
                stock(), stock(2, "ALT001", "Other Technology", "OTHER")));

        var response = new AssistantService(text -> draftFor("001", ""), mapper)
                .draft(new AlertDraftRequest("001 涨到 100 提醒一次"));

        assertThat(response.ready()).isFalse();
        assertThat(response.draft()).isNull();
        assertThat(response.clarificationQuestion())
                .contains("DEMO001", "Demo Technology", "SIM", "ALT001", "OTHER", "补充");
    }

    @Test
    void keepsTheExplicitMarketWhenResolvingAFragment() {
        StockMapper mapper = mock(StockMapper.class);
        when(mapper.findActiveByFragment("%001%", "SIM")).thenReturn(List.of(stock()));

        var response = new AssistantService(text -> draftFor("001", "sim"), mapper)
                .draft(new AlertDraftRequest("SIM 的 001 涨到 100 提醒一次"));

        assertThat(response.ready()).isTrue();
        verify(mapper).findActiveBySymbol("001", "SIM");
        verify(mapper).findActiveByName("001", "SIM");
        verify(mapper, never()).findActiveByFragment(any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void treatsSqlWildcardCharactersAsLiteralStockText() {
        StockMapper mapper = mock(StockMapper.class);
        when(mapper.findActiveByFragment("%AC!%!_!!%", null)).thenReturn(List.of(stock()));

        var response = new AssistantService(text -> draftFor("AC%_!", ""), mapper)
                .draft(new AlertDraftRequest("AC%_! 涨到 100 提醒一次"));

        assertThat(response.ready()).isTrue();
        verify(mapper).findActiveByFragment("%AC!%!_!!%", null);
    }

    @Test
    void asksForAShorterReferenceWithoutSearching() {
        StockMapper mapper = mock(StockMapper.class);

        var response = new AssistantService(text -> draftFor("A".repeat(129), ""), mapper)
                .draft(new AlertDraftRequest("查询股票"));

        assertThat(response.ready()).isFalse();
        verifyNoInteractions(mapper);
    }

    @Test
    void doesNotBroadenTheSearchWhenAnExplicitMarketIsInvalid() {
        StockMapper mapper = mock(StockMapper.class);

        var response = new AssistantService(text -> draftFor("001", "M".repeat(17)), mapper)
                .draft(new AlertDraftRequest("查询指定市场的股票"));

        assertThat(response.ready()).isFalse();
        assertThat(response.clarificationQuestion()).contains("市场代码");
        verifyNoInteractions(mapper);
    }

    @Test
    void returnsClarificationWithoutLookingUpAStockWhenTheModelMarksInputIncomplete() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                false, "你希望价格上涨到多少时提醒？", "DEMO001", "", AlertDirection.ABOVE,
                "0", AlertTriggerMode.ONCE, "", "", "");
        StockMapper stockMapper = mock(StockMapper.class);
        var service = new AssistantService(generator, stockMapper);

        AlertDraftResponse response = service.draft(new AlertDraftRequest("DEMO001 上涨时提醒"));

        assertThat(response.ready()).isFalse();
        assertThat(response.draft()).isNull();
        assertThat(response.clarificationQuestion()).contains("多少");
        verify(stockMapper, never()).findActiveBySymbol("DEMO001", null);
    }

    @Test
    void asksForARearmPriceInsteadOfInventingOneForRepeatAlerts() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "DEMO001", "", AlertDirection.ABOVE, "100", AlertTriggerMode.REPEAT,
                "", "重复提醒", "");
        StockMapper stockMapper = mock(StockMapper.class);
        when(stockMapper.findActiveBySymbol("DEMO001", null)).thenReturn(List.of(stock()));
        var service = new AssistantService(generator, stockMapper);

        AlertDraftResponse response = service.draft(new AlertDraftRequest("DEMO001 每次涨到 100 都提醒"));

        assertThat(response.ready()).isFalse();
        assertThat(response.clarificationQuestion()).contains("回到多少");
    }

    @Test
    void rejectsASymbolThatDoesNotExistInTheBusinessDatabase() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "FAKE", "", AlertDirection.BELOW, "10", AlertTriggerMode.ONCE,
                "", "", "");
        StockMapper stockMapper = mock(StockMapper.class);
        var service = new AssistantService(generator, stockMapper);

        assertThatThrownBy(() -> service.draft(new AlertDraftRequest("FAKE 跌到 10 提醒")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void asksForAMarketWhenTheSymbolIsAmbiguous() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "DEMO001", "", AlertDirection.ABOVE, "100", AlertTriggerMode.ONCE,
                "", "", "");
        StockMapper stockMapper = mock(StockMapper.class);
        when(stockMapper.findActiveBySymbol("DEMO001", null)).thenReturn(List.of(stock(), stock()));

        var response = new AssistantService(generator, stockMapper)
                .draft(new AlertDraftRequest("DEMO001 涨到 100，单次提醒"));

        assertThat(response.ready()).isFalse();
        assertThat(response.draft()).isNull();
        assertThat(response.clarificationQuestion()).contains("市场代码");
    }

    @Test
    void resolvesAnExplicitMarketAndChecksRepeatRearming() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "DEMO001", "sim", AlertDirection.BELOW, "90", AlertTriggerMode.REPEAT,
                "95", "", "");
        StockMapper stockMapper = mock(StockMapper.class);
        when(stockMapper.findActiveBySymbol("DEMO001", "SIM")).thenReturn(List.of(stock()));

        var response = new AssistantService(generator, stockMapper)
                .draft(new AlertDraftRequest("SIM 市场 DEMO001 跌到 90 提醒，回到 95 后可再提醒"));

        assertThat(response.ready()).isTrue();
        assertThat(response.draft().rearmPrice()).isEqualByComparingTo("95");
        assertThat(response.draft().name()).isEqualTo("DEMO001 价格提醒");
    }

    @Test
    void rejectsExcessPricePrecisionInsteadOfRoundingTheModelOutput() {
        AlertDraftGenerator generator = text -> new ModelAlertDraft(
                true, "", "DEMO001", "", AlertDirection.ABOVE, "100.12345", AlertTriggerMode.ONCE,
                "", "", "");
        StockMapper stockMapper = mock(StockMapper.class);
        when(stockMapper.findActiveBySymbol("DEMO001", null)).thenReturn(List.of(stock()));

        assertThatThrownBy(() -> new AssistantService(generator, stockMapper)
                .draft(new AlertDraftRequest("DEMO001 涨到 100.12345 提醒一次")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private Stock stock() {
        return stock(1L, "DEMO001", "Demo Technology", "SIM");
    }

    private Stock stock(long id, String symbol, String name, String market) {
        return new Stock(
                id, symbol, name, market, "USD",
                new BigDecimal("95.0000"), null, "ACTIVE", null, null, "token");
    }

    private ModelAlertDraft draftFor(String reference, String market) {
        return new ModelAlertDraft(true, "", reference, market, AlertDirection.ABOVE, "100",
                AlertTriggerMode.ONCE, "", "", "");
    }
}
