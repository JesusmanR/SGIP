package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.config.ContextoEmpresa;
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

    /**
     * Levanta el bloqueo por intentos fallidos. Devuelve false si el
     * usuario no existe, para que la capa web conteste 404 sin que esta
     * clase tenga que saber de códigos HTTP.
     */
    @Transactional
    /**
     * Levanta el bloqueo de una cuenta de la propia empresa (RF-12).
     *
     * El identificador llega por la ruta, de modo que el usuario se busca
     * acotado por la empresa de quien pide y no por {@code findById}. Con
     * el buscador heredado, el administrador de un comercio podía
     * desbloquear a un usuario de otro: el identificador es único en toda
     * la plataforma y la anotación de rol del controlador solo comprueba
     * que quien pide sea administrador, no de qué empresa. Es la RN-16
     * aplicada a la tabla de usuarios.
     *
     * Devuelve falso tanto si el usuario no existe como si es de otra
     * empresa, y el controlador responde 404 en los dos casos. No es
     * descuido: un 403 para lo ajeno confirmaría que ese usuario existe,
     * que es más de lo que quien pregunta tiene derecho a saber.
     */
    public boolean desbloquear(Integer usuarioId) {
        return usuarios.findByIdAndEmpresaId(usuarioId, ContextoEmpresa.empresaActual())
                .map(u -> {
                    u.desbloquear();
                    return true;
                })
                .orElse(false);
    }

    private void rechazar(Usuario usuario, String correo, MotivoRechazo motivo, String origen) {
        auditoria.save(AuditoriaAcceso.fallido(usuario, correo, motivo, origen));
        throw new CredencialesInvalidasException();
    }
}