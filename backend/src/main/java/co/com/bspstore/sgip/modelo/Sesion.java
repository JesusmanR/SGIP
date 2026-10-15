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

/**
 * Una sesión abierta. Guarda el hash del token, nunca el token: si la
 * base de datos se filtra, las sesiones vigentes no quedan expuestas.
 * Quien tiene el token es el cliente, y solo él.
 *
 * Que la sesión viva en la base de datos es lo que permite cerrarla de
 * verdad (CU-02). Un token firmado y autocontenido no se puede apagar
 * antes de que expire sin mantener aparte una lista de revocados, que
 * es la misma tabla por otro camino y con más piezas.
 */
@Entity
@Table(name = "sesiones")
public class Sesion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sesion_id")
    private Long id;

    /**
     * EAGER a propósito. El filtro de cada petición necesita el usuario y
     * su rol fuera de la transacción que resolvió la sesión, y el
     * proyecto corre con open-in-view en false: una carga perezosa aquí
     * reventaría en el primer uso.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    // La columna es CHAR(64), no VARCHAR: sin declararlo, la validación de
    // esquema al arrancar se queja del tipo. Mismo motivo por el que
    // Usuario anota su TINYINT.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "inicio", nullable = false)
    private LocalDateTime inicio;

    @Column(name = "ultima_actividad", nullable = false)
    private LocalDateTime ultimaActividad;

    @Column(name = "expira", nullable = false)
    private LocalDateTime expira;

    @Column(name = "dispositivo", length = 120)
    private String dispositivo;

    @Column(name = "origen", length = 60)
    private String origen;

    @Column(name = "cerrada", nullable = false)
    private boolean cerrada = false;

    protected Sesion() {
        // Requerido por JPA
    }

    public Sesion(Usuario usuario, String tokenHash, LocalDateTime inicio,
                  LocalDateTime expira, String origen, String dispositivo) {
        this.usuario = usuario;
        this.tokenHash = tokenHash;
        this.inicio = inicio;
        this.ultimaActividad = inicio;
        this.expira = expira;
        this.origen = origen;
        this.dispositivo = dispositivo;
    }

    public boolean estaVigente(LocalDateTime ahora) {
        return !cerrada && ahora.isBefore(expira);
    }

    /**
     * Corre la expiración hacia adelante. La configuración la define como
     * expiración por inactividad, de modo que cada petición la renueva y
     * lo que cierra la sesión es dejar de usarla, no el tiempo total.
     */
    public void renovar(LocalDateTime ahora, int minutos) {
        this.ultimaActividad = ahora;
        this.expira = ahora.plusMinutes(minutos);
    }

    public void cerrar() {
        this.cerrada = true;
    }

    public Long getId() { return id; }
    public Usuario getUsuario() { return usuario; }
    public String getTokenHash() { return tokenHash; }
    public LocalDateTime getInicio() { return inicio; }
    public LocalDateTime getUltimaActividad() { return ultimaActividad; }
    public LocalDateTime getExpira() { return expira; }
    public String getDispositivo() { return dispositivo; }
    public String getOrigen() { return origen; }
    public boolean isCerrada() { return cerrada; }
}
