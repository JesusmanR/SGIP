package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Un proveedor del comercio.
 *
 * No mapea origen_lista, url_servicio ni credencial_servicio. Esas tres
 * columnas sostienen la importación de listas de precios, que la versión
 * 5 del informe movió a la v1.1, y credencial_servicio guarda un secreto
 * cifrado por la aplicación. Mapearlas ahora solo abriría la puerta a
 * exponerlas por un DTO antes de que exista la función que las usa.
 *
 * Las tres admiten nulo o tienen valor por omisión en el esquema, de modo
 * que no mapearlas no impide dar de alta un proveedor.
 */
@Entity
@Table(name = "proveedores")
public class Proveedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proveedor_id")
    private Integer id;

    @Column(name = "empresa_id", nullable = false)
    private Integer empresaId;

    @Column(name = "razon_social", nullable = false, length = 160)
    private String razonSocial;

    /** Único dentro de la empresa desde la V10, no en toda la plataforma. */
    @Column(name = "nit", nullable = false, length = 20)
    private String nit;

    @Column(name = "contacto", length = 120)
    private String contacto;

    @Column(name = "correo", length = 120)
    private String correo;

    @Column(name = "telefono", length = 20)
    private String telefono;

    /** Días de plazo de pago. Cero significa de contado. */
    @Column(name = "plazo_dias", nullable = false)
    private int plazoDias;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    protected Proveedor() {
        // Requerido por JPA
    }

    public Proveedor(Integer empresaId, String razonSocial, String nit) {
        this.empresaId = empresaId;
        this.razonSocial = razonSocial == null ? null : razonSocial.trim();
        this.nit = normalizarNit(nit);
    }

    /**
     * El NIT se guarda sin puntos, espacios ni guiones. Un mismo
     * proveedor escrito «900.123.456-7» y «9001234567» son el mismo, y
     * sin normalizar la restricción única no lo vería.
     */
    public static String normalizarNit(String nit) {
        return nit == null ? null : nit.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    public void contactar(String contacto, String correo, String telefono) {
        this.contacto = contacto;
        this.correo = correo;
        this.telefono = telefono;
    }

    public void fijarPlazo(int dias) {
        this.plazoDias = dias;
    }

    public void desactivar() {
        this.activo = false;
    }

    public Integer getId() { return id; }
    public Integer getEmpresaId() { return empresaId; }
    public String getRazonSocial() { return razonSocial; }
    public String getNit() { return nit; }
    public String getContacto() { return contacto; }
    public String getCorreo() { return correo; }
    public String getTelefono() { return telefono; }
    public int getPlazoDias() { return plazoDias; }
    public boolean isActivo() { return activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
