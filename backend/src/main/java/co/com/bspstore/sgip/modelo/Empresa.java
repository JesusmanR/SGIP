package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

/**
 * La empresa, vista desde dentro de ella.
 *
 * Mapea lo que el comercio consulta o cambia de sí mismo: el logo y los datos de contacto que salen en el
 * recibo (nombre comercial, dirección, ciudad, teléfono y correo). La pantalla de empresa SÍ los edita
 * ({@link #actualizarContacto}).
 *
 * El NIT o la cédula, su tipo y la razón social se leen pero no se editan desde aquí, y el mapeo lo
 * garantiza: están marcados como no insertables y no actualizables, de modo que ningún UPDATE de esta
 * entidad los escribe. No es decoración. Hibernate escribe todas las columnas escribibles en cada UPDATE,
 * así que, si estas fueran escribibles, guardar el contacto con una copia vieja de la empresa pisaría un
 * cambio de identidad que la plataforma hizo entremedias. Esos tres los cambia solo
 * {@code RepositorioPlataforma#cambiarIdentidad}, con motivo y bitácora.
 *
 * {@code @DynamicUpdate}: el UPDATE lleva solo las columnas que cambiaron. El logo se sube tras normalizar
 * la imagen, que tarda, y el contacto se edita desde otra petición; por omisión Hibernate escribe todas las
 * columnas de la fila que leyó y cada una devolvería a la otra a su valor viejo (el mismo caso que
 * {@link Usuario}).
 *
 * El estado de la suscripción y la fecha de alta los gobierna el módulo de plataforma, y traerlos aquí
 * invitaría a que una pantalla de empresa los modificara.
 */
@Entity
@DynamicUpdate
@Table(name = "empresas")
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "empresa_id")
    private Integer id;

    @Column(name = "nit", nullable = false, length = 20, insertable = false, updatable = false)
    private String nit;

    /** NIT o CC (V48). Como el ENUM de la columna: se lee como texto y se expone como {@link TipoDocumentoEmpresa}. */
    @Column(name = "tipo_documento", nullable = false, length = 3, insertable = false, updatable = false)
    private String tipoDocumento;

    @Column(name = "razon_social", nullable = false, length = 160, insertable = false, updatable = false)
    private String razonSocial;

    @Column(name = "nombre_comercial", length = 150)
    private String nombreComercial;

    @Column(name = "direccion", length = 200)
    private String direccion;

    @Column(name = "ciudad", length = 80)
    private String ciudad;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "correo_contacto", length = 120)
    private String correoContacto;

    /** Ruta relativa a la raíz de almacenamiento, no los bytes (V13). */
    @Column(name = "logo_ruta", length = 255)
    private String logoRuta;

    protected Empresa() {
        // Requerido por JPA
    }

    public void cambiarLogo(String rutaRelativa) {
        this.logoRuta = rutaRelativa;
    }

    /**
     * Reemplaza los cinco datos de contacto: es una sustitución, no un parche, de modo que lo que no llega
     * queda vacío. Quien llama recorta los textos y convierte el vacío en nulo.
     */
    public void actualizarContacto(String nombreComercial, String direccion, String ciudad,
                                   String telefono, String correoContacto) {
        this.nombreComercial = nombreComercial;
        this.direccion = direccion;
        this.ciudad = ciudad;
        this.telefono = telefono;
        this.correoContacto = correoContacto;
    }

    public Integer getId() { return id; }
    public String getNit() { return nit; }
    public TipoDocumentoEmpresa getTipoDocumento() { return TipoDocumentoEmpresa.valueOf(tipoDocumento); }
    public String getRazonSocial() { return razonSocial; }
    public String getNombreComercial() { return nombreComercial; }
    public String getDireccion() { return direccion; }
    public String getCiudad() { return ciudad; }
    public String getTelefono() { return telefono; }
    public String getCorreoContacto() { return correoContacto; }
    public String getLogoRuta() { return logoRuta; }
}
