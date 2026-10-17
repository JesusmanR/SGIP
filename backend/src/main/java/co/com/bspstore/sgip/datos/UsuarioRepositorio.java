package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioRepositorio extends JpaRepository<Usuario, Integer> {

    Optional<Usuario> findByCorreo(String correo);

    boolean existsByCorreo(String correo);

    /**
     * Un usuario de una empresa concreta.
     *
     * Existe para que nadie resuelva por {@code findById} un usuario cuyo
     * identificador llegó en la ruta. Con el heredado, el administrador de
     * un comercio puede operar sobre un usuario de otro: el identificador
     * es único en toda la plataforma y la anotación de rol solo comprueba
     * que quien pide sea administrador, no de quién. Es la RN-16 aplicada
     * a la tabla de usuarios.
     */
    Optional<Usuario> findByIdAndEmpresaId(Integer id, Integer empresaId);
}