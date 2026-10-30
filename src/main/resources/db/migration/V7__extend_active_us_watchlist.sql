-- Append active liquid US equities. Preserve existing catalog identity, prices, status and names.
INSERT INTO stocks (symbol,name,market,currency,status) VALUES
    ('ORCL','Oracle Corp','US','USD','ACTIVE'),
    ('SMCI','Super Micro Computer, Inc. Common Stock','US','USD','ACTIVE'),
    ('SOFI','SoFi Technologies, Inc. Common Stock','US','USD','ACTIVE'),
    ('AMD','Advanced Micro Devices, Inc. Common Stock','US','USD','ACTIVE'),
    ('TSLA','Tesla, Inc. Common Stock','US','USD','ACTIVE'),
    ('CRWD','CrowdStrike Holdings, Inc. Class A Common Stock','US','USD','ACTIVE'),
    ('MU','Micron Technology, Inc. Common Stock','US','USD','ACTIVE'),
    ('INTC','Intel Corporation Common Stock','US','USD','ACTIVE'),
    ('BA','Boeing Company','US','USD','ACTIVE'),
    ('HOOD','Robinhood Markets, Inc. Class A Common Stock','US','USD','ACTIVE')
ON DUPLICATE KEY UPDATE symbol=stocks.symbol;
