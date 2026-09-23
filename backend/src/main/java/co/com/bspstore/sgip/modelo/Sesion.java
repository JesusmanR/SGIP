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

import java.time.LocalDateTime;

@Entity
@Table(name = "sesiones")
public class Sesion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sesion_id")
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

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

    public Sesion(Usuario usuario, String tokenHash, int minutosVigencia,
                  String dispositivo, String origen) {
        LocalDateTime ahora = LocalDateTime.now();
        this.usuario = usuario;
        this.tokenHash = tokenHash;
        this.inicio = ahora;
        this.ultimaActividad = ahora;
        this.expira = ahora.plusMinutes(minutosVigencia);
        this.dispositivo = dispositivo;
        this.origen = origen;
    }

    public boolean vigente() {
        return !cerrada && LocalDateTime.now().isBefore(expira);
    }

    public void refrescar(int minutosVigencia) {
        LocalDateTime ahora = LocalDateTime.now();
        this.ultimaActividad = ahora;
        this.expira = ahora.plusMinutes(minutosVigencia);
    }

    public void cerrar() {
        this.cerrada = true;
    }

    public Long getId() { return id; }
    public Usuario getUsuario() { return usuario; }
    public LocalDateTime getExpira() { return expira; }
    public LocalDateTime getUltimaActividad() { return ultimaActividad; }
    public boolean isCerrada() { return cerrada; }
}