ALTER TABLE refuels
    ADD COLUMN station_name VARCHAR(255),
    ADD COLUMN station_address VARCHAR(500),
    ADD COLUMN station_latitude DOUBLE PRECISION,
    ADD COLUMN station_longitude DOUBLE PRECISION;
