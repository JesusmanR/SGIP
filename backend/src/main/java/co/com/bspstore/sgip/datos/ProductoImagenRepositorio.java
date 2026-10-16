package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.ProductoImagen;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductoImagenRepositorio extends JpaRepository<ProductoImagen, Integer> {

    List<ProductoImagen> findByEmpresaIdAndProductoIdOrderByOrden(Integer empresaId, Integer productoId);

    int countByEmpresaIdAndProductoId(Integer empresaId, Integer productoId);

    Optional<ProductoImagen> findByEmpresaIdAndId(Integer empresaId, Integer id);

    Optional<ProductoImagen> findByEmpresaIdAndProductoIdAndPrincipalTrue(
            Integer empresaId, Integer productoId);
}
