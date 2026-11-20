-- V2: datos mínimos para probar la ingesta y la demo
INSERT INTO sitio (nombre) VALUES ('Planta demo UADE');
INSERT INTO sector (sitio_id, nombre) VALUES (1, 'Taller de prueba');
INSERT INTO dispositivo (id, sector_id, descripcion, fw)
VALUES ('nm-001', 1, 'Prototipo ESP32 + MAX9814', '0.1.0');
INSERT INTO dispositivo_estado (device_id) VALUES ('nm-001');
