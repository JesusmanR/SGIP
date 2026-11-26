package co.com.bspstore.sgip.logica.informes;

import co.com.bspstore.sgip.api.dto.InformeInventario;
import co.com.bspstore.sgip.api.dto.InformeInventario.Totales;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioInformeInventario;
import co.com.bspstore.sgip.logica.informes.LibroDeInforme.Formato;
import co.com.bspstore.sgip.util.Hoy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;

import static co.com.bspstore.sgip.logica.informes.LibroDeInforme.celda;

/**
 * El inventario valorizado (RF-57): la pantalla y el Excel.
 *
 * ## Una sola lectura
 *
 * Los totales y la tabla se leen dentro de una transacción {@code REPEATABLE_READ} de solo lectura: una venta
 * que entre mientras se arma la respuesta no puede hacer que las cifras de arriba y las filas de abajo
 * cuenten historias distintas. En el Excel la transacción cubre también el recorrido con cursor.
 *
 * La empresa sale de la sesión ({@link ContextoEmpresa}), nunca de un parámetro.
 */
@Service
public class ServicioInformeInventario {

    static final int TAMANO_POR_OMISION = 50;
    static final int TAMANO_MAXIMO = 200;

    private static final String TITULO = "Inventario valorizado";

    private final RepositorioInformeInventario repositorio;
    private final Clock reloj;

    public ServicioInformeInventario(RepositorioInformeInventario repositorio, Clock reloj) {
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    /**
     * Los totales y una página de la tabla. {@code tamano} se acota a 1..200 (lo que no es positivo usa el
     * tamaño por omisión, 50) y una {@code pagina} negativa es la primera.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InformeInventario pagina(Integer categoriaId, String q, int pagina, int tamano) {
        int tamanoAplicado = tamano <= 0 ? TAMANO_POR_OMISION : Math.min(tamano, TAMANO_MAXIMO);
        return repositorio.pagina(ContextoEmpresa.empresaActual(), categoriaId, q,
                Math.max(pagina, 0), tamanoAplicado);
    }

    /**
     * Escribe el libro completo, sin paginar, en {@code salida}: los filtros usados, los totales y la tabla.
     * Las filas llegan con un cursor y se escriben a medida que llegan. Si algo falla antes de terminar no se
     * escribe ni un byte (ver {@link LibroDeInforme#escribir}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void escribirExcel(Integer categoriaId, String q, OutputStream salida) throws IOException {
        Integer empresa = ContextoEmpresa.empresaActual();
        Totales t = repositorio.totales(empresa, categoriaId, q);
        String categoria = categoriaId == null ? "Todas"
                : repositorio.nombreDeCategoria(empresa, categoriaId).orElse("Categoría " + categoriaId);
        String busqueda = q == null || q.isBlank() ? "Ninguna" : q.trim();

        LibroDeInforme.escribir(salida, TITULO, libro -> {
            libro.filtro("Categoría", categoria);
            libro.filtro("Búsqueda", busqueda);
            libro.total("Referencias en bodega", BigDecimal.valueOf(t.referencias()), Formato.UNIDADES);
            libro.total("Unidades en bodega", BigDecimal.valueOf(t.unidades()), Formato.UNIDADES);
            libro.total("Valor al costo", t.valorAlCosto(), Formato.PESOS);
            libro.total("Valor a precio de contado", t.valorAPrecio(), Formato.PESOS);
            libro.total("Referencias sin precio", BigDecimal.valueOf(t.sinPrecio()), Formato.UNIDADES);
            libro.total("Referencias vendidas sin existencia", BigDecimal.valueOf(t.referenciasNegativas()),
                    Formato.UNIDADES);
            libro.total("Unidades bajo cero", BigDecimal.valueOf(t.unidadesBajoCero()), Formato.UNIDADES);
            libro.total("Valor pendiente al costo", t.valorPendienteAlCosto(), Formato.PESOS);
            libro.encabezados("Código", "Producto", "Categoría", "Existencia", "Costo", "Precio",
                    "Valor al costo", "Valor a precio", "Estado");
            repositorio.recorrer(empresa, categoriaId, q, f -> libro.fila(
                    f.codigo(), f.nombre(), f.categoria(), f.existencia(),
                    celda(f.costo(), Formato.PESOS), celda(f.precio(), Formato.PESOS),
                    celda(f.valorAlCosto(), Formato.PESOS), celda(f.valorAPrecio(), Formato.PESOS),
                    f.activo() ? null : "Inactivo"));
        });
    }

    /** {@code informe-inventario-valorizado-<hoy>.xlsx}, con el día de Bogotá. */
    public String nombreDeArchivo() {
        return LibroDeInforme.nombreDeArchivo("inventario-valorizado", null, Hoy.de(reloj));
    }
}
