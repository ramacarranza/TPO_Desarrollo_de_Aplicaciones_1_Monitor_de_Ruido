-- V1: esquema inicial, tomado del Contrato de datos (Monitor de ruido IIoT)
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE sitio (
  id          SERIAL PRIMARY KEY,
  nombre      TEXT NOT NULL
);

CREATE TABLE sector (
  id              SERIAL PRIMARY KEY,
  sitio_id        INT NOT NULL REFERENCES sitio(id),
  nombre          TEXT NOT NULL,
  umbral_aviso    NUMERIC(4,1) NOT NULL DEFAULT 80.0,  -- dBA
  umbral_alerta   NUMERIC(4,1) NOT NULL DEFAULT 85.0,  -- dBA
  jornada_min     INT NOT NULL DEFAULT 480             -- duración del turno
);

CREATE TABLE dispositivo (
  id          TEXT PRIMARY KEY,                 -- 'nm-001'
  sector_id   INT REFERENCES sector(id),
  descripcion TEXT,
  fw          TEXT,
  activo      BOOLEAN NOT NULL DEFAULT true,
  creado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dispositivo_estado (
  device_id       TEXT PRIMARY KEY REFERENCES dispositivo(id),
  conectado       BOOLEAN NOT NULL DEFAULT false,
  ultimo_estado   TIMESTAMPTZ,
  rssi            INT,
  cola_pendiente  INT,
  mic_ok          BOOLEAN,
  sd_ok           BOOLEAN,
  fuente_hora     TEXT,
  uptime_s        BIGINT
);

CREATE TABLE usuario (
  id            SERIAL PRIMARY KEY,
  email         TEXT UNIQUE NOT NULL,
  hash_clave    TEXT NOT NULL,                  -- bcrypt
  rol           TEXT NOT NULL CHECK (rol IN ('admin','supervisor','operario'))
);

CREATE TABLE medicion (
  ts            TIMESTAMPTZ NOT NULL,
  device_id     TEXT NOT NULL REFERENCES dispositivo(id),
  boot          INT NOT NULL,
  seq           INT NOT NULL,
  periodo_s     SMALLINT NOT NULL,
  laeq          NUMERIC(4,1) NOT NULL,
  lmax          NUMERIC(4,1),
  lmin          NUMERIC(4,1),
  validez       REAL,
  hora_confiable BOOLEAN NOT NULL,              -- false si tsrc = 'none'
  recibido_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (device_id, boot, seq, ts)
);
SELECT create_hypertable('medicion', 'ts');

CREATE TABLE evento (
  id          BIGSERIAL,
  ts          TIMESTAMPTZ NOT NULL,
  device_id   TEXT NOT NULL REFERENCES dispositivo(id),
  boot        INT NOT NULL,
  seq         INT NOT NULL,
  tipo        TEXT NOT NULL,
  codigo      TEXT,
  detalle     TEXT,
  UNIQUE (device_id, boot, seq)
);

CREATE TABLE alerta (
  id              BIGSERIAL PRIMARY KEY,
  creada_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
  device_id       TEXT NOT NULL REFERENCES dispositivo(id),
  tipo            TEXT NOT NULL,   -- NIVEL_AVISO, NIVEL_ALERTA, DOSIS, MIC_FAULT, OFFLINE, SD_FAULT
  valor           NUMERIC(6,1),
  mensaje         TEXT NOT NULL,
  estado          TEXT NOT NULL DEFAULT 'abierta' CHECK (estado IN ('abierta','reconocida','cerrada')),
  reconocida_por  INT REFERENCES usuario(id),
  reconocida_en   TIMESTAMPTZ
);
