-- V5: corrección del docente (nov 2026): sin login, sin roles, sin configuración de umbrales.
-- Las acciones sobre alertas ya no tienen un usuario de la sesión: se guarda un nombre libre opcional
-- que escribe quien usa la app. Los umbrales quedan fijos en configuracion_alertas (80 % y 100 %).

ALTER TABLE intervencion ALTER COLUMN usuario_id DROP NOT NULL;
ALTER TABLE intervencion ADD COLUMN responsable TEXT;

ALTER TABLE alerta
  ADD COLUMN reconocida_por_nombre TEXT,
  ADD COLUMN resuelta_por_nombre   TEXT;

-- Como máximo una alerta OFFLINE y una MIC_FAULT abiertas por sector:
-- los LWT repetidos después de un corte no generan alertas duplicadas.
CREATE UNIQUE INDEX alerta_abierta_por_sector ON alerta (sector_id, tipo)
  WHERE tipo IN ('OFFLINE', 'MIC_FAULT') AND estado <> 'RESUELTA';
