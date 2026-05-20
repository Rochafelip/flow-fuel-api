-- Converte vehicles.energy_type de INTEGER (ORDINAL) para VARCHAR (STRING).
-- Mapeamento: 0 -> COMBUSTION, 1 -> ELECTRIC, 2 -> HYBRID.
-- Usa coluna temporaria para preservar dados e evitar conflitos de tipo.

ALTER TABLE vehicles ADD COLUMN energy_type_tmp VARCHAR(20);

UPDATE vehicles SET energy_type_tmp = CASE energy_type
    WHEN 0 THEN 'COMBUSTION'
    WHEN 1 THEN 'ELECTRIC'
    WHEN 2 THEN 'HYBRID'
END;

ALTER TABLE vehicles DROP COLUMN energy_type;
ALTER TABLE vehicles RENAME COLUMN energy_type_tmp TO energy_type;
ALTER TABLE vehicles ALTER COLUMN energy_type SET NOT NULL;
