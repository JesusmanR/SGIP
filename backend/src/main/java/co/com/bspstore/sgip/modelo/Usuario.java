package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usuario_id")
    private Integer id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rol_id", nullable = false)
    private Rol rol;

    /**
     * Empresa a la que pertenece. Admite nulo a propósito: el usuario de
     * plataforma no pertenece a ninguna (RN-17), y la V10 lo deja así de
     * forma explícita. Se mapea como el identificador y no como una
     * relación porque nada de lo que hay hasta ahora necesita la fila de
     * empresas, y traerla en cada petición costaría una consulta más.
     */
    @Column(name = "empresa_id")
    private Integer empresaId;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "correo", nullable = false, length = 120, unique = true)
    private String correo;

    @Column(name = "clave_hash", nullable = false, length = 255)
    private String claveHash;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "bloqueado", nullable = false)
    private boolean bloqueado = false;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(name = "intentos_fallidos", nullable = false)
    private int intentosFallidos = 0;

    @Column(name = "cambio_clave_req", nullable = false)
    private boolean cambioClaveRequerido = true;

    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en", insertable = false, updatable = false)
    private LocalDateTime actualizadoEn;

    protected Usuario() {
        // Requerido por JPA
    }

    public Usuario(Rol rol, String nombre, String correo, String claveHash) {
        this.rol = rol;
        this.nombre = nombre;
        this.correo = correo.trim().toLowerCase(Locale.ROOT);
        this.claveHash = claveHash;
    }

    public boolean puedeIngresar() {
        return activo && !bloqueado;
    }

    public void registrarIntentoFallido(int maximoPermitido) {
        intentosFallidos++;
        if (intentosFallidos >= maximoPermitido) {
            bloqueado = true;
        }
    }

    public void registrarIngresoExitoso() {
        intentosFallidos = 0;
    }

    public void desbloquear() {
        bloqueado = false;
        intentosFallidos = 0;
    }

    public void cambiarClave(String nuevoHash) {
        this.claveHash = nuevoHash;
        this.cambioClaveRequerido = false;
    }

    public void desactivar() {
        this.activo = false;
    }

    /**
     * Cambia el nombre y el rol (RF-12).
     *
     * Los dos juntos y en un solo método porque son lo único que la
     * edición permite cambiar, y tenerlos separados invitaría a añadir
     * un tercero. Lo que no está aquí es tan importante como lo que
     * está: no hay forma de cambiar el correo, que es la credencial y lo
     * que la auditoría de accesos guarda; ni la clave, porque un
     * administrador que pudiera fijarla podría entrar como esa persona y
     * el rastro dejaría de identificar a nadie; ni la empresa, que es la
     * garantía del RF-12.
     */
    public void reasignar(String nombre, Rol rol) {
        this.nombre = nombre;
        this.rol = rol;
    }

    /** Vuelve a habilitar una cuenta desactivada (RF-12). */
    public void activar() {
        this.activo = true;
    }

    /**
     * Asigna la empresa a la que pertenece. Nulo es válido y significa
     * usuario de plataforma, que no opera dentro de ninguna (RN-17).
     */
    public void asignarEmpresa(Integer empresaId) {
        this.empresaId = empresaId;
    }

    public Integer getId() { return id; }
    public Rol getRol() { return rol; }
    public Integer getEmpresaId() { return empresaId; }
    public String getNombre() { return nombre; }
    public String getCorreo() { return correo; }
    public String getClaveHash() { return claveHash; }
    public boolean isActivo() { return activo; }
    public boolean isBloqueado() { return bloqueado; }
    public int getIntentosFallidos() { return intentosFallidos; }
    public boolean isCambioClaveRequerido() { return cambioClaveRequerido; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}