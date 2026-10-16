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
    private final NormalizadorImagenes normalizador;

    /** Lado de la foto de producto, ya recortada a cuadrado. */
    private static final int LADO_PRODUCTO = 800;
    /** Cuadro máximo del logo. No se recorta: cabe entero dentro. */
    private static final int LADO_LOGO = 400;

    public ServicioImagenes(ProductoImagenRepositorio imagenes,
                            ProductoRepositorio productos,
                            EmpresaRepositorio empresas,
                            AlmacenArchivos almacen,
                            NormalizadorImagenes normalizador) {
        this.imagenes = imagenes;
        this.productos = productos;
        this.empresas = empresas;
        this.almacen = almacen;
        this.normalizador = normalizador;
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

        // Se normaliza antes de guardar: lo que llega al disco ya mide
        // igual que todo lo demás. Hacerlo al mostrar arreglaría una sola
        // pantalla y dejaría al documento exportable con el problema.
        byte[] normalizada = normalizador.cuadrada(
                almacen.leerValidado(archivo), LADO_PRODUCTO);

        // El archivo se guarda antes de insertar la fila: si la escritura
        // falla, no queda una fila apuntando a un archivo inexistente. Al
        // revés sí puede quedar un archivo huérfano, que es inofensivo.
        String ruta = almacen.guardar(normalizada, empresa, "productos");

        boolean esLaPrimera = cuantas == 0;
        short siguiente = (short) (imagenes.mayorOrden(empresa, productoId) + 1);
        return imagenes.save(new ProductoImagen(empresa, productoId, ruta,
                siguiente, esLaPrimera, normalizada.length));
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

    /**
     * Borra una imagen: la fila y el archivo.
     *
     * Las imágenes sí se eliminan, a diferencia del resto del esquema,
     * donde nada se borra. Una foto equivocada no es un registro
     * contable: no hay nada que auditar en ella y dejarla desactivada
     * solo ocuparía sitio en la galería del producto.
     *
     * Si era la principal, otra ocupa su lugar. Un producto con imágenes
     * pero sin principal no aparecería en el catálogo exportable.
     */
    @Transactional
    public void eliminar(Integer imagenId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        ProductoImagen imagen = imagenes.findByEmpresaIdAndId(empresa, imagenId)
                .orElseThrow(() -> new ReferenciaAjenaException(
                        "La imagen indicada no existe en esta empresa."));

        boolean eraLaPrincipal = imagen.isPrincipal();
        Integer productoId = imagen.getProductoId();
        String ruta = imagen.getRuta();

        imagenes.delete(imagen);
        imagenes.flush();   // libera el orden y la marca de principal

        if (eraLaPrincipal) {
            imagenes.findByEmpresaIdAndProductoIdOrderByOrden(empresa, productoId)
                    .stream().findFirst()
                    .ifPresent(ProductoImagen::hacerPrincipal);
        }

        // El archivo se borra al final: si algo falla antes, la fila
        // sigue apuntando a un archivo que existe.
        almacen.borrar(ruta);
    }

    @Transactional
    public Empresa subirLogo(MultipartFile archivo) {
        Integer empresaId = ContextoEmpresa.empresaActual();
        Empresa empresa = empresas.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "La sesión apunta a una empresa que no existe: " + empresaId));

        // El logo se ajusta, no se recorta: cortarlo le quitaría el nombre
        // a media empresa, y muchos son alargados.
        byte[] normalizado = normalizador.ajustada(
                almacen.leerValidado(archivo), LADO_LOGO);
        empresa.cambiarLogo(almacen.guardar(normalizado, empresaId, "logo"));
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
