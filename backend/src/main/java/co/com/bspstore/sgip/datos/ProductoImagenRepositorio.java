package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.ProductoImagen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductoImagenRepositorio extends JpaRepository<ProductoImagen, Integer> {

    List<ProductoImagen> findByEmpresaIdAndProductoIdOrderByOrden(Integer empresaId, Integer productoId);

    int countByEmpresaIdAndProductoId(Integer empresaId, Integer productoId);

    Optional<ProductoImagen> findByEmpresaIdAndId(Integer empresaId, Integer id);

    Optional<ProductoImagen> findByEmpresaIdAndProductoIdAndPrincipalTrue(
            Integer empresaId, Integer productoId);

    /**
     * El mayor orden usado, no cuántas hay. Borrar una imagen intermedia
     * deja huecos, y numerar la siguiente por el conteo chocaría contra
     * uq_producto_imagenes_orden.
     */
    @Query("SELECT COALESCE(MAX(i.orden), 0) FROM ProductoImagen i "
            + "WHERE i.empresaId = :empresaId AND i.productoId = :productoId")
    short mayorOrden(@Param("empresaId") Integer empresaId,
                     @Param("productoId") Integer productoId);
}
