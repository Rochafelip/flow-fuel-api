-- Baseline FlowFuel — schema inicial (PostgreSQL).
-- Atencao: gerado para refletir o estado atual das entidades JPA. Em ambientes
-- ja existentes, use spring.flyway.baseline-on-migrate=true.

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password        VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    phone           VARCHAR(255),
    profile_picture VARCHAR(255),
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

CREATE TABLE vehicles (
    id                BIGSERIAL PRIMARY KEY,
    type              VARCHAR(255) NOT NULL,
    energy_type       INTEGER NOT NULL,
    fuel_sub_type     VARCHAR(255),
    current_km        INTEGER NOT NULL,
    capacity          INTEGER NOT NULL,
    brand             VARCHAR(255),
    model             VARCHAR(255),
    manufacture_year  INTEGER,
    model_year        INTEGER,
    color             VARCHAR(255),
    license_plate     VARCHAR(255),
    photo             VARCHAR(255),
    is_active         BOOLEAN DEFAULT TRUE,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP,
    user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_vehicles_user_id ON vehicles(user_id);

CREATE TABLE refuels (
    id                    BIGSERIAL PRIMARY KEY,
    refuel_date           TIMESTAMP,
    odometer              INTEGER NOT NULL,
    km_since_last_refuel  INTEGER,
    energy_amount         NUMERIC(19, 2) NOT NULL,
    price_per_unit        NUMERIC(19, 2) NOT NULL,
    total_amount          NUMERIC(19, 2),
    full_tank             BOOLEAN DEFAULT FALSE,
    vehicle_id            BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE
);

CREATE INDEX idx_refuels_vehicle_id ON refuels(vehicle_id);
CREATE INDEX idx_refuels_refuel_date ON refuels(refuel_date);
