-- V3: alinear el modelo con el TPO (Contrato de datos v2)
-- Agrega empresa, turno por sector, roles del TPO, umbrales en %, alertas nuevas e intervenciones.

CREATE TABLE empresa (
  id      SERIAL PRIMARY KEY,
  nombre  TEXT NOT NULL
);
ALTER TABLE sitio ADD COLUMN empresa_id INT REFERENCES empresa(id);

-- Turno del sector: inicio + duración (jornada_min ya existía). No cruza la medianoche.
-- umbral_aviso y umbral_alerta (dBA) quedan sin uso: los umbrales pasan a ser % por empresa.
ALTER TABLE sector
  ADD COLUMN turno_inicio   TIME        NOT NULL DEFAULT '08:00',
  ADD COLUMN actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE usuario
  ADD COLUMN empresa_id INT REFERENCES empresa(id),
  ADD COLUMN nombre     TEXT,
  ADD COLUMN legajo     TEXT,
  ADD COLUMN sector_id  INT REFERENCES sector(id);
ALTER TABLE usuario DROP CONSTRAINT usuario_rol_check;
ALTER TABLE usuario ADD CONSTRAINT usuario_rol_check
  CHECK (rol IN ('SEGURIDAD_HIGIENE', 'TRABAJADOR'));
ALTER TABLE usuario ADD CONSTRAINT trabajador_con_sector
  CHECK (rol <> 'TRABAJADOR' OR sector_id IS NOT NULL);
CREATE UNIQUE INDEX usuario_legajo ON usuario (empresa_id, legajo) WHERE legajo IS NOT NULL;

CREATE TABLE configuracion_alertas (
  empresa_id      INT PRIMARY KEY REFERENCES empresa(id),
  atencion_pct    NUMERIC(5,1) NOT NULL DEFAULT 80.0,
  critico_pct     NUMERIC(5,1) NOT NULL DEFAULT 100.0,
  actualizado_por INT REFERENCES usuario(id),
  actualizado_en  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (atencion_pct > 0 AND atencion_pct < critico_pct AND critico_pct <= 200)
);

-- alerta se rehace: cambia el modelo (por sector, tipos y estados del TPO). Estaba vacía.
DROP TABLE alerta;
CREATE TABLE alerta (
  id             BIGSERIAL PRIMARY KEY,
  sector_id      INT  NOT NULL REFERENCES sector(id),
  device_id      TEXT REFERENCES dispositivo(id),
  tipo           TEXT NOT NULL CHECK (tipo IN ('ATENCION', 'CRITICO', 'OFFLINE', 'MIC_FAULT')),
  fecha_turno    DATE,                 -- turno al que pertenece (ATENCION y CRITICO)
  valor          NUMERIC(6,1),         -- % de exposición observado al crearse
  mensaje        TEXT NOT NULL,        -- ya redactado para el usuario
  estado         TEXT NOT NULL DEFAULT 'PENDIENTE'
                 CHECK (estado IN ('PENDIENTE', 'RECONOCIDA', 'RESUELTA')),
  creada_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
  reconocida_por INT REFERENCES usuario(id),
  reconocida_en  TIMESTAMPTZ,
  resuelta_por   INT REFERENCES usuario(id),   -- NULL si la resolvió el sistema
  resuelta_en    TIMESTAMPTZ,
  actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Como máximo una alerta de Atención y una Crítica por sector y turno
CREATE UNIQUE INDEX alerta_unica_por_turno ON alerta (sector_id, tipo, fecha_turno)
  WHERE tipo IN ('ATENCION', 'CRITICO');
CREATE INDEX alerta_estado ON alerta (estado, creada_en DESC);

CREATE TABLE intervencion (
  id             UUID PRIMARY KEY,     -- lo genera la app: reintentar no duplica
  alerta_id      BIGINT NOT NULL REFERENCES alerta(id),
  usuario_id     INT    NOT NULL REFERENCES usuario(id),
  descripcion    TEXT   NOT NULL CHECK (length(descripcion) BETWEEN 5 AND 2000),
  realizada_en   TIMESTAMPTZ NOT NULL, -- cuándo se hizo (puede ser antes de sincronizar)
  creada_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX intervencion_alerta ON intervencion (alerta_id);
