package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Categoria;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Ningún método consulta sin empresa, y es deliberado: un
 * {@code findAll()} heredado de JpaRepository devolvería las categorías
 * de todos los comercios. Los que se declaran aquí llevan la empresa en
 * la firma, de modo que olvidarla no compila.
 */
public interface CategoriaRepositorio extends JpaRepository<Categoria, Integer> {

    List<Categoria> findByEmpresaIdAndActivaTrueOrderByNombre(Integer empresaId);

    Optional<Categoria> findByEmpresaIdAndId(Integer empresaId, Integer id);

    boolean existsByEmpresaIdAndNombreIgnoreCase(Integer empresaId, String nombre);

    /**
     * Excluye a la propia categoria: al editar sin cambiar el nombre, el
     * que ya tiene no puede contar como repetido consigo mismo.
     */
    boolean existsByEmpresaIdAndNombreIgnoreCaseAndIdNot(Integer empresaId, String nombre,
                                                         Integer id);
}
