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
@Table(name = "auditoria_accesos")
public class AuditoriaAcceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "acceso_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "correo", nullable = false, length = 120)
    private String correo;

    @Column(name = "exitoso", nullable = false)
    private boolean exitoso;

    @Column(name = "motivo", length = 60)
    private String motivo;

    @Column(name = "origen", length = 60)
    private String origen;

    @Column(name = "fecha", insertable = false, updatable = false)
    private LocalDateTime fecha;

    protected AuditoriaAcceso() {
        // Requerido por JPA
    }

    private AuditoriaAcceso(Usuario usuario, String correo, boolean exitoso,
                            MotivoRechazo motivo, String origen) {
        this.usuario = usuario;
        this.correo = correo;
        this.exitoso = exitoso;
        this.motivo = motivo == null ? null : motivo.name();
        this.origen = origen;
    }

    public static AuditoriaAcceso exitoso(Usuario usuario, String origen) {
        return new AuditoriaAcceso(usuario, usuario.getCorreo(), true, null, origen);
    }

    public static AuditoriaAcceso fallido(Usuario usuario, String correo,
                                          MotivoRechazo motivo, String origen) {
        return new AuditoriaAcceso(usuario, correo, false, motivo, origen);
    }

    /**
     * Un hecho de la bitácora que no es un ingreso, como los de la recuperación de
     * la contraseña. A diferencia de las otras dos fábricas acepta que no haya
     * usuario (el correo pedido puede no existir) y deja que el motivo diga si fue
     * un éxito o un rechazo. El correo y el origen vienen de quien llama a la API
     * y la columna no los admite más largos: se recortan aquí, para que un correo
     * escrito a propósito muy largo no haga fallar la inserción de la auditoría.
     */
    public static AuditoriaAcceso evento(Usuario usuarioONull, String correo, boolean exitoso,
                                         MotivoRechazo motivo, String origen) {
        return new AuditoriaAcceso(usuarioONull, recortar(correo, 120), exitoso, motivo,
                recortar(origen, 60));
    }

    private static String recortar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    public Long getId() { return id; }
    public String getCorreo() { return correo; }
    public boolean isExitoso() { return exitoso; }
    public String getMotivo() { return motivo; }
    public String getOrigen() { return origen; }
}