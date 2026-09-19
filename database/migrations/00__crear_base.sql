-- =====================================================================
-- SGIP · Creación de la base de datos
-- Ejecutar una sola vez, antes de las migraciones V1 a V7.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS sgip
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE sgip;

-- Requerido para crear funciones almacenadas cuando el servidor tiene
-- activado el registro binario. Necesita privilegio SUPER.
-- SET GLOBAL log_bin_trust_function_creators = 1;
