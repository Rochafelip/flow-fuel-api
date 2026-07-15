CREATE TABLE vehicle_shares (
    id             BIGSERIAL PRIMARY KEY,
    vehicle_id     BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    owner_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    guest_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL,
    duration_days  INTEGER NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    responded_at   TIMESTAMP,
    expires_at     TIMESTAMP
);

CREATE INDEX idx_vehicle_shares_guest_status ON vehicle_shares(guest_id, status);

-- So permite um share PENDING/ACTIVE por veiculo por vez.
CREATE UNIQUE INDEX idx_vehicle_shares_active_unique ON vehicle_shares(vehicle_id)
    WHERE status IN ('PENDING', 'ACTIVE');
