package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.Empresa;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Aquí sí vale findById heredado: el identificador no viene de la
 * petición sino de la sesión, a través de ContextoEmpresa.
 */
public interface EmpresaRepositorio extends JpaRepository<Empresa, Integer> {
}
