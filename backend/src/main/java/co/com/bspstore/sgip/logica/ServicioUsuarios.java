package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudEdicionUsuario;
import co.com.bspstore.sgip.api.dto.SolicitudUsuario;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Los usuarios del propio negocio, administrados por su administrador
 * (RF-12).
 *
 * Dos garantías gobiernan toda la clase, y las dos se consiguen por
 * omisión antes que por validación:
 *
 * <ul>
 *   <li><b>La empresa sale del contexto, nunca de la petición.</b> Las
 *       solicitudes no tienen campo de empresa, de modo que no hay
 *       número que mandar para crear o mover un usuario a otro comercio.
 *       Omitir el campo es más fuerte que comprobarlo.</li>
 *   <li><b>Lo ajeno se responde como lo inexistente.</b> Cada búsqueda
 *       va acotada por empresa y devuelve vacío, que el controlador
 *       traduce a 404. Un 403 confirmaría que ese usuario existe en
 *       alguna parte, que es más de lo que quien pregunta tiene derecho
 *       a saber.</li>
 * </ul>
 */
@Service
public class ServicioUsuarios {

    /**
     * Los roles que un administrador de empresa puede repartir.
     *
     * SUPER_ADMIN no está, y es la línea más importante del archivo. Ese
     * rol gobierna el ciclo de vida de todas las empresas y los datos
     * tributarios del país; si un administrador pudiera asignarlo, se
     * daría a sí mismo —o a un cómplice— el mando de la plataforma desde
     * la pantalla de usuarios de su propia tienda. El requisito no lo
     * dice porque no se le ocurrió que hiciera falta decirlo; la RN-17 sí
     * lo implica, al reservar ese papel a quien opera la plataforma.
     *
     * Se enumera lo permitido y no lo prohibido: un rol nuevo que alguien
     * añada mañana no se reparte hasta que se decida que se reparte.
     */
    private static final Set<String> ROLES_ASIGNABLES =
            Set.of("ADMINISTRADOR", "VENDEDOR", "CLIENTE");

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private final UsuarioRepositorio usuarios;
    private final RolRepositorio roles;

    public ServicioUsuarios(UsuarioRepositorio usuarios, RolRepositorio roles) {
        this.usuarios = usuarios;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarios.findByEmpresaIdOrderByNombre(ContextoEmpresa.empresaActual());
    }

    @Transactional(readOnly = true)
    public Optional<Usuario> buscar(Integer usuarioId) {
        return usuarios.findByIdAndEmpresaId(usuarioId, ContextoEmpresa.empresaActual());
    }

    @Transactional
    public Usuario crear(SolicitudUsuario solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        String correo = solicitud.correo().trim().toLowerCase(Locale.ROOT);

        // La unicidad del correo es de toda la plataforma, no de la
        // empresa: es la credencial con la que se entra y el ingreso no
        // sabe todavía a qué comercio pertenece quien escribe.
        if (usuarios.existsByCorreo(correo)) {
            throw new RegistroRepetidoException(
                    "El correo " + correo + " ya pertenece a un usuario de la plataforma.");
        }

        Rol rol = rolAsignable(solicitud.rol());
        Usuario nuevo = new Usuario(rol, solicitud.nombre().trim(), correo,
                Seguridad.derivar(solicitud.clave()));
        nuevo.asignarEmpresa(empresa);
        return usuarios.save(nuevo);
    }

    /**
     * Cambia el nombre y el rol. Vacío si el usuario no es de esta
     * empresa.
     *
     * Bajar de rol al último administrador activo se niega por lo mismo
     * que desactivarlo: dejaría la empresa sin nadie que pueda
     * administrarla.
     */
    @Transactional
    public Optional<Usuario> actualizar(Integer usuarioId, SolicitudEdicionUsuario solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Usuario> encontrado = usuarios.findByIdAndEmpresaId(usuarioId, empresa);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Usuario usuario = encontrado.get();
        Rol rol = rolAsignable(solicitud.rol());

        boolean dejaDeSerAdministrador = ROL_ADMINISTRADOR.equals(usuario.getRol().getNombre())
                && !ROL_ADMINISTRADOR.equals(rol.getNombre())
                && usuario.isActivo();
        if (dejaDeSerAdministrador) {
            exigirOtroAdministrador(empresa,
                    "Es el único administrador activo de la empresa. "
                            + "Nombra otro antes de cambiarle el rol.");
        }

        usuario.reasignar(solicitud.nombre().trim(), rol);
        return Optional.of(usuarios.save(usuario));
    }

    /**
     * Desactiva una cuenta, que no es borrarla: la auditoría de accesos
     * y todo lo que esa persona registró siguen apuntando a ella.
     *
     * Se niega en dos casos. Contra uno mismo, porque quien se desactiva
     * pierde la sesión a mitad de la operación y no tiene cómo
     * deshacerla. Y contra el último administrador activo, porque la
     * empresa quedaría sin nadie que pueda crear usuarios —eso es cosa de
     * su administrador, y el super administrador no entra en los
     * comercios (RN-17)—, de modo que recuperarla pediría intervenir la
     * base a mano.
     */
    @Transactional
    public Optional<Usuario> desactivar(Integer usuarioId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Optional<Usuario> encontrado = usuarios.findByIdAndEmpresaId(usuarioId, empresa);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Usuario usuario = encontrado.get();

        if (usuarioId.equals(quienPregunta())) {
            throw new UltimoAdministradorException(
                    "No puedes desactivar tu propia cuenta: perderías la sesión sin "
                            + "poder deshacerlo. Pídeselo a otro administrador.");
        }
        if (ROL_ADMINISTRADOR.equals(usuario.getRol().getNombre()) && usuario.isActivo()) {
            exigirOtroAdministrador(empresa,
                    "Es el único administrador activo de la empresa. "
                            + "Nombra otro antes de desactivarlo.");
        }

        usuario.desactivar();
        return Optional.of(usuarios.save(usuario));
    }

    /** Vuelve a habilitar una cuenta desactivada. */
    @Transactional
    public Optional<Usuario> activar(Integer usuarioId) {
        Integer empresa = ContextoEmpresa.empresaActual();
        return usuarios.findByIdAndEmpresaId(usuarioId, empresa)
                .map(u -> {
                    u.activar();
                    return usuarios.save(u);
                });
    }

    /**
     * El rol pedido, si es uno de los que un administrador reparte.
     *
     * Un rol desconocido y un rol no asignable se distinguen en el
     * mensaje pero acaban en el mismo sitio: el administrador no puede
     * usarlo. No se filtra el que exista pero no se reparta diciendo «no
     * existe», porque entonces nadie entendería por qué «SUPER_ADMIN» no
     * funciona.
     */
    private Rol rolAsignable(String nombre) {
        String limpio = nombre == null ? "" : nombre.trim().toUpperCase(Locale.ROOT);
        if (!ROLES_ASIGNABLES.contains(limpio)) {
            throw new RolNoAsignableException(
                    "El administrador de una empresa puede asignar "
                            + String.join(", ", ordenados()) + ". El rol «" + limpio
                            + "» no está entre ellos.");
        }
        return roles.findByNombre(limpio).orElseThrow(() -> new IllegalStateException(
                "El rol " + limpio + " no está en la base. La semilla de la V7 no se aplicó."));
    }

    /** Ordenados para que el mensaje de error no cambie entre llamadas. */
    private static List<String> ordenados() {
        return ROLES_ASIGNABLES.stream().sorted().toList();
    }

    private void exigirOtroAdministrador(Integer empresa, String mensaje) {
        long administradores = usuarios.countByEmpresaIdAndRol_NombreAndActivoTrue(
                empresa, ROL_ADMINISTRADOR);
        if (administradores <= 1) {
            throw new UltimoAdministradorException(mensaje);
        }
    }

    private Integer quienPregunta() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Operación sin usuario en el contexto. Revise la cadena de seguridad."));
    }
}
