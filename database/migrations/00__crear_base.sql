-- =====================================================================
-- SGIP · Creación de la base de datos
-- Ejecutar una sola vez, antes de la cadena de migraciones, que hoy va
-- de la V1 a la V20. El orden lo fija database/aplicar.sh: no es el
-- alfabético, porque así 'V10' quedaría antes de 'V2'.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS sgip
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE sgip;

-- Requerido para crear funciones almacenadas cuando el servidor tiene
-- activado el registro binario. Necesita privilegio SUPER.
-- SET GLOBAL log_bin_trust_function_creators = 1;
