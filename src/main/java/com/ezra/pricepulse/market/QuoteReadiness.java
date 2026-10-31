package com.ezra.pricepulse.market;

import com.ezra.pricepulse.stock.Stock;

/** Read-only transport/continuity gate; never infers health from an absence of trades. */
public interface QuoteReadiness {
    boolean canConfirmLatest(Stock stock,MarketDataModels.StreamState state);
}
