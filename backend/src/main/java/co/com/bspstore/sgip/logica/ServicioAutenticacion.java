package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.datos.AuditoriaAccesoRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AuditoriaAcceso;
import co.com.bspstore.sgip.modelo.MotivoRechazo;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import co.com.bspstore.sgip.datos.SesionRepositorio;
import co.com.bspstore.sgip.modelo.Sesion;

import java.util.Locale;
import java.util.Optional;

@Service
public class ServicioAutenticacion {

    private final UsuarioRepositorio usuarios;
    private final AuditoriaAccesoRepositorio auditoria;
    private final int intentosMaximos;
    private final SesionRepositorio sesiones;
    private final int minutosVigencia;

    public ServicioAutenticacion(UsuarioRepositorio usuarios,
                                 AuditoriaAccesoRepositorio auditoria,
                                 SesionRepositorio sesiones,
                                 @Value("${sgip.seguridad.intentos-maximos:5}") int intentosMaximos,
                                 @Value("${sgip.seguridad.sesion-minutos:30}") int minutosVigencia) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.sesiones = sesiones;
        this.intentosMaximos = intentosMaximos;
        this.minutosVigencia = minutosVigencia;
    }

    @Transactional(noRollbackFor = CredencialesInvalidasException.class)
    public SesionEmitida autenticar(String correo, String clave, String dispositivo, String origen) {
        String normalizado = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);

        Usuario usuario = usuarios.findByCorreo(normalizado).orElse(null);
        if (usuario == null) {
            rechazar(null, normalizado, MotivoRechazo.USUARIO_INEXISTENTE, origen);
        }
        if (!usuario.isActivo()) {
            rechazar(usuario, normalizado, MotivoRechazo.CUENTA_INACTIVA, origen);
        }
        if (usuario.isBloqueado()) {
            rechazar(usuario, normalizado, MotivoRechazo.CUENTA_BLOQUEADA, origen);
        }
        if (!Seguridad.verificar(clave, usuario.getClaveHash())) {
            usuario.registrarIntentoFallido(intentosMaximos);
            rechazar(usuario, normalizado, MotivoRechazo.CLAVE_INCORRECTA, origen);
        }



        usuario.registrarIngresoExitoso();
        auditoria.save(AuditoriaAcceso.exitoso(usuario, origen));

        String token = Seguridad.nuevoToken();
        Sesion sesion = new Sesion(usuario, Seguridad.huella(token),
                minutosVigencia, dispositivo, origen);
        sesiones.save(sesion);

        return new SesionEmitida(usuario, token, sesion.getExpira());
    }

    private void rechazar(Usuario usuario, String correo, MotivoRechazo motivo, String origen) {
        auditoria.save(AuditoriaAcceso.fallido(usuario, correo, motivo, origen));
        throw new CredencialesInvalidasException();
    }
    @Transactional
    public void cerrar(String token) {
        sesiones.findByTokenHash(Seguridad.huella(token))
                .ifPresent(Sesion::cerrar);
    }

    @Transactional
    public Optional<Usuario> validarToken(String token) {
        return sesiones.findByTokenHash(Seguridad.huella(token))
                .filter(Sesion::vigente)
                .map(sesion -> {
                    sesion.refrescar(minutosVigencia);
                    return sesion.getUsuario();
                });
    }
}