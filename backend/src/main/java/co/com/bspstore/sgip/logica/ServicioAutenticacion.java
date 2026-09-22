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

import java.util.Locale;

@Service
public class ServicioAutenticacion {

    private final UsuarioRepositorio usuarios;
    private final AuditoriaAccesoRepositorio auditoria;
    private final int intentosMaximos;

    public ServicioAutenticacion(UsuarioRepositorio usuarios,
                                 AuditoriaAccesoRepositorio auditoria,
                                 @Value("${sgip.seguridad.intentos-maximos:5}") int intentosMaximos) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.intentosMaximos = intentosMaximos;
    }

    @Transactional(noRollbackFor = CredencialesInvalidasException.class)
    public Usuario autenticar(String correo, String clave, String origen) {
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
        return usuario;
    }

    private void rechazar(Usuario usuario, String correo, MotivoRechazo motivo, String origen) {
        auditoria.save(AuditoriaAcceso.fallido(usuario, correo, motivo, origen));
        throw new CredencialesInvalidasException();
    }
}