-- Cria tabela vehicle_events: eventos financeiros/operacionais ligados a um veiculo
-- (manutencao, troca de oleo, lavagem, pneus, seguro, imposto, documentos, combustivel avulso).
-- Nao substitui refuels: nao calcula consumo, nao valida odometro, nao atualiza vehicle.current_km.

CREATE TABLE vehicle_events (
    id          BIGSERIAL PRIMARY KEY,
    vehicle_id  BIGINT NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
    type        VARCHAR(32) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    description VARCHAR(2000),
    odometer    INTEGER,
    event_date  DATE NOT NULL,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);

CREATE INDEX idx_ve_vehicle_date ON vehicle_events(vehicle_id, event_date);
CREATE INDEX idx_ve_vehicle_type ON vehicle_events(vehicle_id, type);
