package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Producto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Lado de escritura. La lectura del catálogo con precios no pasa por
 * aquí: va contra v_precios_producto a través de ConsultaCatalogo
 * (ADR-0003).
 *
 * Como en categorías, ninguna consulta prescinde de la empresa.
 */
public interface ProductoRepositorio extends JpaRepository<Producto, Integer> {

    Optional<Producto> findByEmpresaIdAndId(Integer empresaId, Integer id);

    boolean existsByEmpresaIdAndCodigo(Integer empresaId, String codigo);

    boolean existsByEmpresaIdAndSku(Integer empresaId, String sku);

    /**
     * Las dos de abajo excluyen al propio producto. Al editar, el codigo
     * que ya tiene no puede contar como repetido consigo mismo: sin el
     * «AndIdNot», guardar sin cambiar el codigo fallaria con que el
     * codigo ya existe.
     */
    boolean existsByEmpresaIdAndCodigoAndIdNot(Integer empresaId, String codigo, Integer id);

    boolean existsByEmpresaIdAndSkuAndIdNot(Integer empresaId, String sku, Integer id);

    /**
     * Para negarse a desactivar una categoria que todavia sostiene
     * productos. Cuenta solo los activos: los ya desactivados no
     * necesitan una categoria viva.
     */
    long countByEmpresaIdAndCategoriaIdAndActivoTrue(Integer empresaId, Integer categoriaId);
}
