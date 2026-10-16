package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.EmpresaRepositorio;
import co.com.bspstore.sgip.datos.ProductoImagenRepositorio;
import co.com.bspstore.sgip.datos.ProductoRepositorio;
import co.com.bspstore.sgip.modelo.Empresa;
import co.com.bspstore.sgip.modelo.ProductoImagen;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class ServicioImagenes {

    /** El RF-63 admite cinco por producto, y el esquema lo hace cumplir. */
    private static final int MAXIMO_POR_PRODUCTO = 5;

    private final ProductoImagenRepositorio imagenes;
    private final ProductoRepositorio productos;
    private final EmpresaRepositorio empresas;
    private final AlmacenArchivos almacen;

    public ServicioImagenes(ProductoImagenRepositorio imagenes,
                            ProductoRepositorio productos,
                            EmpresaRepositorio empresas,
                            AlmacenArchivos almacen) {
        this.imagenes = imagenes;
        this.productos = productos;
        this.empresas = empresas;
        this.almacen = almacen;
    }

    @Transactional(readOnly = true)
    public List<ProductoImagen> listar(Integer productoId) {
        return imagenes.findByEmpresaIdAndProductoIdOrderByOrden(
                ContextoEmpresa.empresaActual(), productoId);
    }

    @Transactional
    public ProductoImagen subirDeProducto(Integer productoId, MultipartFile archivo) {
        Integer empresa = ContextoEmpresa.empresaActual();

        // El producto tiene que ser de esta empresa. Si no se comprobara,
        // bastaría con poner el identificador de un producto ajeno en la
        // ruta para colgarle una imagen.
        productos.findByEmpresaIdAndId(empresa, productoId)
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "El producto indicado no existe en esta empresa."));

        int cuantas = imagenes.countByEmpresaIdAndProductoId(empresa, productoId);
        if (cuantas >= MAXIMO_POR_PRODUCTO) {
            throw new ArchivoInvalidoException(
                    "Un producto admite " + MAXIMO_POR_PRODUCTO + " imágenes y ya tiene "
                            + cuantas + ". Elimina alguna antes de subir otra.");
        }

        // El archivo se guarda antes de insertar la fila: si la escritura
        // falla, no queda una fila apuntando a un archivo inexistente. Al
        // revés sí puede quedar un archivo huérfano, que es inofensivo.
        String ruta = almacen.guardar(archivo, empresa, "productos");

        boolean esLaPrimera = cuantas == 0;
        return imagenes.save(new ProductoImagen(empresa, productoId, ruta,
                (short) (cuantas + 1), esLaPrimera, (int) archivo.getSize()));
    }

    /**
     * Cambia cuál es la principal. Hay que apagar la anterior antes de
     * encender la nueva: el índice único sobre la columna generada
     * rechazaría tener dos a la vez, y con razón.
     */
    @Transactional
    public ProductoImagen marcarPrincipal(Integer imagenId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        ProductoImagen nueva = imagenes.findByEmpresaIdAndId(empresa, imagenId)
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "La imagen indicada no existe en esta empresa."));

        imagenes.findByEmpresaIdAndProductoIdAndPrincipalTrue(empresa, nueva.getProductoId())
                .filter(anterior -> !anterior.getId().equals(imagenId))
                .ifPresent(ProductoImagen::dejarDeSerPrincipal);

        imagenes.flush();   // apaga la anterior antes de encender esta
        nueva.hacerPrincipal();
        return nueva;
    }

    @Transactional
    public Empresa subirLogo(MultipartFile archivo) {
        Integer empresaId = ContextoEmpresa.empresaActual();
        Empresa empresa = empresas.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "La sesión apunta a una empresa que no existe: " + empresaId));

        empresa.cambiarLogo(almacen.guardar(archivo, empresaId, "logo"));
        return empresa;
    }

    @Transactional(readOnly = true)
    public Empresa empresaActual() {
        Integer empresaId = ContextoEmpresa.empresaActual();
        return empresas.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "La sesión apunta a una empresa que no existe: " + empresaId));
    }
}
