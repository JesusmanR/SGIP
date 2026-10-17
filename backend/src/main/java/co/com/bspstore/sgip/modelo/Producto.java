package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * El producto tal como se guarda.
 *
 * Deliberadamente NO mapea estado_stock ni ningún precio de venta.
 * estado_stock es una columna generada por el motor y los precios salen
 * de v_precios_producto, porque dependen de las reglas de margen y de
 * impuesto, que viven en otras tablas (ADR-0003). Para leer el catálogo
 * con sus precios está {@code ConsultaCatalogo}; esta clase sirve para
 * escribirlo.
 *
 * Las claves foráneas se mapean como identificadores y no como
 * relaciones: la cadena categoría-proveedor-empresa se verifica en el
 * servicio contra la empresa del contexto, y traer las filas enteras en
 * cada alta no aportaría nada.
 */
@Entity
@Table(name = "productos")
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "producto_id")
    private Integer id;

    @Column(name = "empresa_id", nullable = false)
    private Integer empresaId;

    @Column(name = "categoria_id", nullable = false)
    private Integer categoriaId;

    @Column(name = "proveedor_id", nullable = false)
    private Integer proveedorId;

    @Column(name = "codigo", nullable = false, length = 60)
    private String codigo;

    /**
     * Referencia interna del comercio. La V10 la agregó y la rellenó con
     * el código para las filas que ya existían, de modo que es obligatoria
     * aunque muchos comercios la dejen igual al código.
     */
    @Column(name = "sku", nullable = false, length = 40)
    private String sku;

    @Column(name = "codigo_barras", length = 60)
    private String codigoBarras;

    @Column(name = "nombre", nullable = false, length = 160)
    private String nombre;

    /** «500 g», «1 litro». Lo que distingue dos presentaciones del mismo producto. */
    @Column(name = "presentacion", length = 60)
    private String presentacion;

    @Column(name = "descripcion", length = 400)
    private String descripcion;

    @Column(name = "costo", nullable = false, precision = 14, scale = 2)
    private BigDecimal costo;

    @Column(name = "existencia", nullable = false)
    private int existencia;

    @Column(name = "existencia_minima", nullable = false)
    private int existenciaMinima = 5;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private LocalDateTime actualizadoEn;

    protected Producto() {
        // Requerido por JPA
    }

    public Producto(Integer empresaId, Integer categoriaId, Integer proveedorId,
                    String codigo, String sku, String nombre, BigDecimal costo) {
        this.empresaId = empresaId;
        this.categoriaId = categoriaId;
        this.proveedorId = proveedorId;
        this.codigo = normalizar(codigo);
        this.sku = normalizar(sku);
        this.nombre = nombre == null ? null : nombre.trim();
        this.costo = costo;
    }

    /** Códigos y SKU se guardan en mayúsculas y sin espacios al borde. */
    private static String normalizar(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    public void describir(String presentacion, String descripcion, String codigoBarras) {
        this.presentacion = presentacion;
        this.descripcion = descripcion;
        this.codigoBarras = codigoBarras;
    }

    public void fijarExistencias(int existencia, int existenciaMinima) {
        this.existencia = existencia;
        this.existenciaMinima = existenciaMinima;
    }

    /**
     * Cambia el costo. No toca el precio de venta porque el producto no
     * lo guarda: lo calcula la vista con la regla de margen vigente.
     */
    public void cambiarCosto(BigDecimal nuevo) {
        this.costo = nuevo;
    }

    /**
     * Reemplaza los datos que la pantalla de edición deja cambiar.
     *
     * La existencia no está entre ellos, y no es un olvido. Toda
     * variación de existencia tiene que quedar registrada con su valor
     * anterior y posterior (RF-49), de modo que corregirla escribiendo
     * un número en un formulario dejaría un salto sin explicación en la
     * única tabla que permite reconstruir qué pasó con una referencia.
     * Ese camino es el movimiento de inventario, no este.
     */
    public void reemplazarDatos(Integer categoriaId, Integer proveedorId,
                                String codigo, String sku, String nombre,
                                String presentacion, String descripcion,
                                String codigoBarras, BigDecimal costo,
                                int existenciaMinima) {
        this.categoriaId = categoriaId;
        this.proveedorId = proveedorId;
        this.codigo = normalizar(codigo);
        this.sku = normalizar(sku);
        this.nombre = nombre == null ? null : nombre.trim();
        this.presentacion = presentacion;
        this.descripcion = descripcion;
        this.codigoBarras = codigoBarras;
        this.costo = costo;
        this.existenciaMinima = existenciaMinima;
    }

    public void desactivar() {
        this.activo = false;
    }

    public Integer getId() { return id; }
    public Integer getEmpresaId() { return empresaId; }
    public Integer getCategoriaId() { return categoriaId; }
    public Integer getProveedorId() { return proveedorId; }
    public String getCodigo() { return codigo; }
    public String getSku() { return sku; }
    public String getCodigoBarras() { return codigoBarras; }
    public String getNombre() { return nombre; }
    public String getPresentacion() { return presentacion; }
    public String getDescripcion() { return descripcion; }
    public BigDecimal getCosto() { return costo; }
    public int getExistencia() { return existencia; }
    public int getExistenciaMinima() { return existenciaMinima; }
    public boolean isActivo() { return activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public LocalDateTime getActualizadoEn() { return actualizadoEn; }
}
