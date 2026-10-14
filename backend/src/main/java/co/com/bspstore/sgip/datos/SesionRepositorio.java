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
}
