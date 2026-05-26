-- Adiciona suporte a veiculos hibridos:
--   * refuels.refuel_type identifica se o abastecimento foi FUEL ou ELECTRIC.
--   * vehicles.battery_capacity guarda a capacidade da bateria (kWh) para
--     veiculos ELECTRIC e HYBRID.
-- Backfill: refuels existentes recebem o tipo derivado do energy_type do veiculo.

ALTER TABLE vehicles ADD COLUMN battery_capacity NUMERIC(8, 2);

ALTER TABLE refuels ADD COLUMN refuel_type VARCHAR(16);

UPDATE refuels
   SET refuel_type = CASE
       WHEN (SELECT v.energy_type FROM vehicles v WHERE v.id = refuels.vehicle_id) = 'ELECTRIC'
           THEN 'ELECTRIC'
       ELSE 'FUEL'
   END;

ALTER TABLE refuels ALTER COLUMN refuel_type SET NOT NULL;

CREATE INDEX idx_refuels_refuel_type ON refuels(refuel_type);
