-- Usuarios de la muestra local de SGIP.
--
-- Lo carga muestra/lib/muestra.sh una sola vez, DESPUÉS de que el backend
-- haya aplicado sus migraciones. Espera estas variables de sesión (el
-- guion las calcula; los hashes salen con PBKDF2, igual que el backend):
--   @correo_admin, @correo_vendedor, @correo_plataforma
--   @h_admin, @h_vendedor, @h_plataforma, @h_pin
--
-- Las claves de prueba están solo en muestra/LEEME.md. Aquí solo hay hashes
-- que se generan al vuelo, y nunca van a producción.
--
-- Es repetible: cada alta se apoya en el correo, que es único.

SET time_zone = '-05:00';

-- La empresa de la muestra es la 1 (Black Steel Parts, la de la V8). Debe
-- poder escribir en la fecha de la presentación: ACTIVA no depende de la
-- prueba gratuita. La suscripción queda vigente por si algo la consulta.
UPDATE empresas SET estado = 'ACTIVA' WHERE empresa_id = 1;
UPDATE suscripciones
   SET estado = 'AL_DIA', vigente_hasta = '2027-12-31'
 WHERE empresa_id = 1;

-- cambio_clave_req = 0: la muestra no obliga a cambiar la clave al entrar.
INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash,
                      activo, bloqueado, intentos_fallidos, cambio_clave_req)
SELECT 1, r.rol_id, 'Administrador de la muestra', @correo_admin, @h_admin, 1, 0, 0, 0
  FROM roles r WHERE r.nombre = 'ADMINISTRADOR'
ON DUPLICATE KEY UPDATE nombre = VALUES(nombre);

INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash,
                      activo, bloqueado, intentos_fallidos, cambio_clave_req)
SELECT 1, r.rol_id, 'Vendedor de la muestra', @correo_vendedor, @h_vendedor, 1, 0, 0, 0
  FROM roles r WHERE r.nombre = 'VENDEDOR'
ON DUPLICATE KEY UPDATE nombre = VALUES(nombre);

-- El usuario de la plataforma no pertenece a ninguna empresa.
INSERT INTO usuarios (empresa_id, rol_id, nombre, correo, clave_hash,
                      activo, bloqueado, intentos_fallidos, cambio_clave_req)
SELECT NULL, r.rol_id, 'Plataforma de la muestra', @correo_plataforma, @h_plataforma, 1, 0, 0, 0
  FROM roles r WHERE r.nombre = 'SUPER_ADMIN'
ON DUPLICATE KEY UPDATE nombre = VALUES(nombre);

-- PIN del administrador (autorizaciones de la caja). En develop todavía no
-- existe la columna: se omite sin error. La rama de la muestra, que sí trae
-- la caja, debe ajustar el nombre de la columna aquí si no es `pin_hash`.
SET @tiene_pin = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'usuarios'
                     AND column_name = 'pin_hash');
SET @orden = IF(@tiene_pin > 0,
                'UPDATE usuarios SET pin_hash = @h_pin WHERE correo = @correo_admin',
                'DO 0');
PREPARE poner_pin FROM @orden;
EXECUTE poner_pin;
DEALLOCATE PREPARE poner_pin;
