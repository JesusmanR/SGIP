package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Sesion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SesionRepositorio extends JpaRepository<Sesion, Long> {

    Optional<Sesion> findByTokenHash(String tokenHash);
}