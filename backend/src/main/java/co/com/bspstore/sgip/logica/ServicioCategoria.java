package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.modelo.Categoria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * La empresa la toma del contexto, nunca de un parámetro que venga de la
 * petición. Ese es el punto único del que habla la RN-16: si el servicio
 * la recibiera desde fuera, bastaría con cambiar un número en la llamada
 * para leer el catálogo de otro comercio.
 */
@Service
public class ServicioCategoria {

    private final CategoriaRepositorio categorias;

    public ServicioCategoria(CategoriaRepositorio categorias) {
        this.categorias = categorias;
    }

    @Transactional(readOnly = true)
    public List<Categoria> listar() {
        return categorias.findByEmpresaIdAndActivaTrueOrderByNombre(ContextoEmpresa.empresaActual());
    }

    @Transactional(readOnly = true)
    public Optional<Categoria> buscar(Integer categoriaId) {
        return categorias.findByEmpresaIdAndId(ContextoEmpresa.empresaActual(), categoriaId);
    }

    @Transactional
    public Categoria crear(String nombre, String descripcion) {
        Integer empresa = ContextoEmpresa.empresaActual();
        String limpio = nombre == null ? "" : nombre.trim();
        if (categorias.existsByEmpresaIdAndNombreIgnoreCase(empresa, limpio)) {
            throw new NombreRepetidoException("Ya existe una categoría llamada «" + limpio + "».");
        }
        return categorias.save(new Categoria(empresa, limpio, descripcion));
    }
}
