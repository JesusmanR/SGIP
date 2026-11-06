package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;

/**
 * Una fila de v_precios_producto, tal como la ve el catálogo.
 *
 * Los nombres siguen los de la vista para que el mapeo sea directo. No
 * incluye empresa_id: el cliente no necesita saber a qué empresa
 * pertenece lo que pidió, porque sale de su propia sesión.
 *
 * Tres campos merecen explicación, porque no son datos del producto sino
 * avisos que la vista calcula:
 *
 * tributacionSinConfigurar · la empresa se declaró responsable de IVA y el
 * producto no tiene regla de impuesto, de modo que se venderá al precio y sin
 * impuesto aunque la empresa diga que lo cobra: falta decir cuál. Es un aviso
 * y no impide vender: sin regla, el total es el precio (ADR-0024). Una
 * empresa que no es responsable de IVA no tiene nada que configurar y
 * siempre recibe 0. Vale 1 también para el producto excluido hasta el umbral
 * mientras falte la UVT del año en curso: sin la cifra no se puede demostrar
 * la exclusión y se cobra la tarifa (V35). Tampoco impide vender.
 *
 * cercaUmbral · el precio quedó en el último diez por ciento antes del
 * umbral del artículo 424. Un ajuste pequeño de costo lo vuelve gravado.
 *
 * estadoStock · lo clasifica el motor de base de datos como columna
 * generada, no la aplicación, de modo que no puede desincronizarse de la
 * existencia real.
 *
 * imagenPrincipalId · cuál es la imagen principal, para que el cliente
 * pida su miniatura por una dirección que no cambia de contenido a
 * espaldas de nadie. Es el identificador, nunca la ruta: la organización
 * interna de las carpetas no sale al exterior y no hay ninguna cadena
 * del cliente que llegue al sistema de archivos.
 *
 * baseContado y baseCredito · cuál de las dos formas de cálculo aplicó
 * la regla vigente, SOBRE_COSTO o SOBRE_PRECIO. El RF-32 pide que «la
 * interfaz indique cuál de las dos bases está aplicando», y la pantalla
 * no tiene cómo saberlo mirando el precio: dos productos con el mismo
 * precio pueden venir de reglas distintas.
 *
 * totalCredito · el precio a crédito con su impuesto. Faltaba, y su
 * ausencia no era menor: en este modelo el umbral del Art. 424 se mide
 * contra el precio de venta, de modo que un producto puede quedar
 * excluido de contado y gravado a crédito. Quien ofrecía el crédito
 * mirando solo precioCredito se quedaba corto.
 *
 * cruzaUmbralEnCredito · justamente ese caso, nombrado. Es la única de
 * estas columnas que no se puede deducir mirando otra.
 *
 * tieneImagen · lo mismo en forma de sí o no, que es lo que la tabla
 * necesita para decidir si ofrece «ver» o «añadir» imágenes.
 *
 * margenContado y margenCredito · la ganancia real sobre el costo, medida
 * contra el precio ya redondeado. La calcula la vista para que los reportes la
 * lean de un solo sitio. Sin precio, nula.
 *
 * precioFijoContado y precioFijoCredito · la cifra, cuando lo que se escribió
 * para el producto fue el precio: sube el costo y ese precio se conserva.
 *
 * porcentajeContado y porcentajeCredito · el porcentaje sobre el costo que
 * declara la regla vigente, nulo con un precio fijo. No es el margen: con
 * costo 7 al 20 % el precio es 8 y el margen 14,29 %, pero lo guardado es 20.
 * Es lo que abre el formulario.
 *
 * version · la versión del producto, tal como la guarda `productos.version`.
 * Quien edita la devuelve en el `PUT`, y si el producto cambió entretanto (una
 * carga de precios, otra edición) el servidor lo rechaza con un 409 en lugar de
 * pisar el cambio. No sale de la vista, que no la lleva: la lee `ConsultaCatalogo`
 * de la fila del producto por su clave primaria. Un cambio solo de precio no la
 * sube (ADR-0025). Es del administrador: quien vende no edita.
 *
 * Esta es la forma completa, con costo y proveedor, y solo la recibe el
 * administrador. Quien vende recibe {@link ProductoParaVenta}.
 */
public record ProductoEnCatalogo(
        Integer productoId,
        String codigo,
        String sku,
        String nombre,
        String presentacion,
        String descripcion,
        String codigoBarras,
        Integer categoriaId,
        String categoria,
        Integer proveedorId,
        String proveedor,
        BigDecimal costo,
        Integer existencia,
        Integer existenciaMinima,
        String estadoStock,
        BigDecimal precioContado,
        BigDecimal precioCredito,
        String tratamientoIva,
        BigDecimal tarifaContado,
        BigDecimal totalContado,
        BigDecimal totalCredito,
        String baseContado,
        String baseCredito,
        Integer tributacionSinConfigurar,
        Integer cercaUmbral,
        Integer cercaUmbralCredito,
        Integer cruzaUmbralEnCredito,
        Integer imagenPrincipalId,
        Integer tieneImagen,
        BigDecimal margenContado,
        BigDecimal margenCredito,
        BigDecimal precioFijoContado,
        BigDecimal precioFijoCredito,
        BigDecimal porcentajeContado,
        BigDecimal porcentajeCredito,
        Integer version
) implements VistaDeProducto {}
