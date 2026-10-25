INSERT INTO stocks (symbol, name, market, currency, latest_price, status)
VALUES
    ('DEMO001', 'Demo Technology', 'SIM', 'USD', 95.0000, 'ACTIVE'),
    ('DEMO002', 'Demo Consumer', 'SIM', 'USD', 120.0000, 'ACTIVE'),
    ('DEMO003', 'Demo Energy', 'SIM', 'USD', 48.5000, 'ACTIVE') AS incoming
ON DUPLICATE KEY UPDATE
    name = incoming.name,
    currency = incoming.currency,
    status = incoming.status;
