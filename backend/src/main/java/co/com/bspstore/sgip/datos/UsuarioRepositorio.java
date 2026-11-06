package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /** Los usuarios del propio negocio, activos y desactivados (RF-12). */
    List<Usuario> findByEmpresaIdOrderByNombre(Integer empresaId);

    /**
     * Los usuarios de una empresa con su rol, en una sola consulta.
     *
     * El rol es EAGER: con un método derivado, Hibernate lo busca después con
     * una consulta por cada rol distinto. Aquí viene unido y la lista cuesta
     * lo mismo con dos usuarios que con doscientos.
     */
    @Query("select u from Usuario u join fetch u.rol where u.empresaId = :empresaId order by u.nombre")
    List<Usuario> listarConRolDeLaEmpresa(@Param("empresaId") Integer empresaId);

    /**
     * Cuántos administradores activos le quedan a una empresa.
     *
     * Existe para no dejarla sin ninguno. Una empresa cuyo último
     * administrador se desactiva queda sin nadie que pueda crear
     * usuarios, porque eso es cosa de su administrador y el super
     * administrador no entra en los comercios (RN-17): haría falta
     * intervenir la base a mano.
     */
    long countByEmpresaIdAndRol_NombreAndActivoTrue(Integer empresaId, String rol);
}