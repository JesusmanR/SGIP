package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Rol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RolRepositorio extends JpaRepository<Rol, Integer> {

    Optional<Rol> findByNombre(String nombre);
}