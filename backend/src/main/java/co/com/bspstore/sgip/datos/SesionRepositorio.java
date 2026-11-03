package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Sesion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SesionRepositorio extends JpaRepository<Sesion, Long> {

    Optional<Sesion> findByTokenHash(String tokenHash);

    /**
     * Cierra de golpe todas las sesiones abiertas de un usuario. Se usa
     * al desbloquear o desactivar una cuenta: si alguien entró con ella
     * antes del bloqueo, su token seguiría sirviendo.
     */
    @Modifying
    @Query("UPDATE Sesion s SET s.cerrada = true "
            + "WHERE s.usuario.id = :usuarioId AND s.cerrada = false")
    int cerrarLasDe(@Param("usuarioId") Integer usuarioId);

    /**
     * Cierra las sesiones abiertas de un usuario menos una, la que se
     * conserva. Se usa al cambiar la contraseña: las demás pudieron abrirse
     * con la clave anterior, y quien la tuviera seguiría dentro.
     *
     * La tabla no tiene {@code empresa_id}: la empresa de una sesión es la
     * de su usuario, que el servicio recibe de la sesión autenticada y no
     * de la petición. Acotar por usuario es acotar por empresa.
     */
    @Modifying
    @Query("UPDATE Sesion s SET s.cerrada = true "
            + "WHERE s.usuario.id = :usuarioId AND s.cerrada = false "
            + "AND s.tokenHash <> :tokenHashActual")
    int cerrarLasDemasDe(@Param("usuarioId") Integer usuarioId,
                         @Param("tokenHashActual") String tokenHashActual);
}
