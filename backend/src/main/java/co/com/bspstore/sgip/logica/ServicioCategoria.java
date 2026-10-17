package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.CategoriaRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
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
    private final ProductoRepositorio productos;

    public ServicioCategoria(CategoriaRepositorio categorias, ProductoRepositorio productos) {
        this.categorias = categorias;
        this.productos = productos;
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

    /**
     * Edita el nombre y la descripción de una categoría (RF-19).
     *
     * Devuelve vacío si la categoría no es de esta empresa, de modo que
     * el controlador responda 404 y no un 500 por tocar algo ajeno.
     */
    @Transactional
    public Optional<Categoria> actualizar(Integer categoriaId, String nombre, String descripcion) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Categoria> encontrada = categorias.findByEmpresaIdAndId(empresa, categoriaId);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        String limpio = nombre == null ? "" : nombre.trim();
        if (categorias.existsByEmpresaIdAndNombreIgnoreCaseAndIdNot(empresa, limpio, categoriaId)) {
            throw new NombreRepetidoException("Ya existe una categoría llamada «" + limpio + "».");
        }
        Categoria c = encontrada.get();
        c.renombrar(limpio, descripcion);
        return Optional.of(categorias.save(c));
    }

    /**
     * Desactiva una categoría, que no es lo mismo que borrarla: los
     * productos que la usaron conservan su historial.
     *
     * Se niega mientras sostenga productos activos. Permitirlo dejaría
     * el catálogo en un estado que ninguna pantalla sabe presentar: el
     * producto seguiría mostrando una categoría que ya no aparece en
     * ninguna lista, y su formulario de edición no podría
     * preseleccionarla. Primero se mueven los productos.
     */
    @Transactional
    public Optional<Categoria> desactivar(Integer categoriaId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Categoria> encontrada = categorias.findByEmpresaIdAndId(empresa, categoriaId);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        long enUso = productos.countByEmpresaIdAndCategoriaIdAndActivoTrue(empresa, categoriaId);
        if (enUso > 0) {
            throw new CategoriaEnUsoException(
                    "La categoría sostiene " + enUso + " producto(s) activo(s). "
                            + "Muévelos a otra categoría antes de desactivarla.");
        }
        Categoria c = encontrada.get();
        c.desactivar();
        return Optional.of(categorias.save(c));
    }
}
