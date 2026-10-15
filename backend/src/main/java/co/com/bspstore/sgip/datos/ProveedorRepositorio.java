package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Proveedor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Como en el resto del catálogo, ninguna consulta prescinde de la empresa. */
public interface ProveedorRepositorio extends JpaRepository<Proveedor, Integer> {

    List<Proveedor> findByEmpresaIdAndActivoTrueOrderByRazonSocial(Integer empresaId);

    Optional<Proveedor> findByEmpresaIdAndId(Integer empresaId, Integer id);

    boolean existsByEmpresaIdAndNit(Integer empresaId, String nit);
}
