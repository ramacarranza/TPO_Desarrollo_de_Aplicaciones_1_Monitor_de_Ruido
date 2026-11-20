-- V4: datos de demo del Contrato v2.
-- Una empresa, una planta y tres sectores: Taller de prueba (sensor físico nm-001),
-- Prensas (nm-002) y Depósito (nm-003), estos dos con el simulador.
-- Usuarios de demo, clave "demo1234" (bcrypt $2a$, compatible con Java/Kotlin).

INSERT INTO empresa (nombre) VALUES ('Empresa Demo');

UPDATE sitio
   SET nombre = 'Planta Demo',
       empresa_id = (SELECT id FROM empresa WHERE nombre = 'Empresa Demo')
 WHERE nombre = 'Planta demo UADE';

-- Turnos en hora argentina. El del Taller cubre la tarde y la noche (14 a 22 h)
-- para probar con el sensor real; se cambia con un UPDATE si hace falta.
UPDATE sector SET turno_inicio = '14:00' WHERE nombre = 'Taller de prueba';

INSERT INTO sector (sitio_id, nombre, turno_inicio)
SELECT id, 'Prensas', '08:00' FROM sitio WHERE nombre = 'Planta Demo';
INSERT INTO sector (sitio_id, nombre, turno_inicio)
SELECT id, 'Depósito', '08:00' FROM sitio WHERE nombre = 'Planta Demo';

INSERT INTO dispositivo (id, sector_id, descripcion, fw)
SELECT 'nm-002', id, 'Sensor simulado', 'sim' FROM sector WHERE nombre = 'Prensas';
INSERT INTO dispositivo (id, sector_id, descripcion, fw)
SELECT 'nm-003', id, 'Sensor simulado', 'sim' FROM sector WHERE nombre = 'Depósito';
INSERT INTO dispositivo_estado (device_id) VALUES ('nm-002'), ('nm-003');

INSERT INTO configuracion_alertas (empresa_id)
SELECT id FROM empresa WHERE nombre = 'Empresa Demo';

INSERT INTO usuario (email, hash_clave, rol, empresa_id, nombre, legajo, sector_id)
SELECT 'sh@demo.local',
       '$2a$10$gW4os.eryioRWapMGaHEkuDMY3TxwBrsex1.Gp2o5ZEg4ZZPDXcta',
       'SEGURIDAD_HIGIENE', e.id, 'Usuario Seguridad e Higiene', 'SH001', NULL
  FROM empresa e WHERE e.nombre = 'Empresa Demo';

INSERT INTO usuario (email, hash_clave, rol, empresa_id, nombre, legajo, sector_id)
SELECT 'trabajador@demo.local',
       '$2a$10$gW4os.eryioRWapMGaHEkuDMY3TxwBrsex1.Gp2o5ZEg4ZZPDXcta',
       'TRABAJADOR', e.id, 'Trabajador Prensas', '1001', s.id
  FROM empresa e, sector s
 WHERE e.nombre = 'Empresa Demo' AND s.nombre = 'Prensas';
