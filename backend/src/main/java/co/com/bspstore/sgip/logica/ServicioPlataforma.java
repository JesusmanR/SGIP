package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * El ciclo de vida de las empresas (RF-01 a RF-03).
 *
 * Es el único servicio del proyecto que no pasa por
 * {@link ContextoEmpresa#empresaActual()}, y conviene que llame la
 * atención. El resto lo usa para acotar cada consulta a la empresa de
 * quien pregunta; aquí quien pregunta no tiene empresa —es el super
 * administrador de la RN-17— y el identificador llega por la ruta a
 * propósito, porque administrar el ciclo de vida de un comercio consiste
 * precisamente en nombrarlo desde fuera.
 *
 * Eso deja la autorización como única barrera, de modo que está puesta
 * donde no se puede olvidar: en {@code @PreAuthorize} del controlador,
 * que es lo que la cadena de seguridad evalúa antes de entrar aquí.
 */
@Service
public class ServicioPlataforma {

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";
    private static final String PLAN_POR_OMISION = "BASICO";

    private final RepositorioPlataforma plataforma;
    private final UsuarioRepositorio usuarios;
    private final RolRepositorio roles;
    private final Clock reloj;

    public ServicioPlataforma(RepositorioPlataforma plataforma,
                              UsuarioRepositorio usuarios,
                              RolRepositorio roles,
                              Clock reloj) {
        this.plataforma = plataforma;
        this.usuarios = usuarios;
        this.roles = roles;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public List<EmpresaEnPlataforma> listar() {
        return plataforma.listar();
    }

    @Transactional(readOnly = true)
    public Optional<EmpresaEnPlataforma> buscar(Integer empresaId) {
        return plataforma.buscar(empresaId);
    }

    /**
     * Da de alta una empresa con su administrador inicial (RF-01).
     *
     * Las cinco escrituras —empresa, suscripción, configuración, usuario
     * y bitácora— van en una sola transacción, y no es celo: a medias
     * queda una empresa en la que nadie puede entrar, y nadie de fuera
     * puede crearle el primer usuario, porque crear usuarios es cosa de
     * su propio administrador (RF-12). El estado irrecuperable no sería
     * un dato perdido, sería un comercio inservible que además ocupa su
     * identificación tributaria.
     *
     * Los dos choques posibles se comprueban antes de escribir para
     * poder decir cuál es. La clave única de la base sigue ahí debajo por
     * si dos altas simultáneas pasan la comprobación a la vez: entonces
     * una de las dos revienta con un 1062 y la transacción se deshace
     * entera, que es el resultado correcto aunque el mensaje sea peor.
     */
    @Transactional
    public EmpresaEnPlataforma darDeAlta(SolicitudAltaEmpresa solicitud) {
        String nit = texto(solicitud.nit());
        String correoAdmin = solicitud.administradorCorreo().trim().toLowerCase(Locale.ROOT);

        if (plataforma.existeNit(nit)) {
            throw new RegistroRepetidoException(
                    "Ya hay una empresa registrada con la identificación tributaria " + nit + ".");
        }
        if (usuarios.existsByCorreo(correoAdmin)) {
            throw new RegistroRepetidoException(
                    "El correo " + correoAdmin + " ya pertenece a un usuario de la plataforma.");
        }

        Integer empresaId = plataforma.insertarEmpresa(
                nit,
                texto(solicitud.razonSocial()),
                texto(solicitud.correoContacto()),
                texto(solicitud.telefono()),
                texto(solicitud.ciudad()));

        LocalDate hoy = LocalDate.now(reloj);
        // En meses y no en días, que es lo que pide el RF-04: «hasta el
        // mismo día del mes siguiente». `plusMonths` recorta solo cuando
        // ese día no existe —el 31 de enero vence el 28 de febrero—, que
        // es la única lectura posible del criterio en esos casos.
        plataforma.insertarSuscripcion(
                empresaId,
                solicitud.plan() == null || solicitud.plan().isBlank()
                        ? PLAN_POR_OMISION : solicitud.plan().trim(),
                hoy,
                hoy.plusMonths(plataforma.mesesDePrueba()));

        plataforma.insertarConfiguracion(empresaId);

        Rol rolAdministrador = roles.findByNombre(ROL_ADMINISTRADOR)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe el rol " + ROL_ADMINISTRADOR
                                + ". La semilla de la V7 no se aplicó."));
        Usuario administrador = new Usuario(
                rolAdministrador,
                texto(solicitud.administradorNombre()),
                correoAdmin,
                Seguridad.derivar(solicitud.administradorClave()));
        administrador.asignarEmpresa(empresaId);
        usuarios.save(administrador);

        plataforma.registrar(responsable(), empresaId, AccionPlataforma.EMPRESA_ALTA,
                "empresas", nit, null, EstadoEmpresa.PRUEBA.name(),
                "administrador inicial " + correoAdmin);

        // Se devuelve lo que quedó en la base y no lo que se pidió: así la
        // respuesta lleva las fechas y el conteo tal como el motor los
        // calculó, en lugar de lo que este método cree que escribió.
        return plataforma.buscar(empresaId).orElseThrow(() -> new IllegalStateException(
                "La empresa " + empresaId + " no se encontró justo después de crearla."));
    }

    /** RF-02. Vacío si la empresa no existe, para que el controlador responda 404. */
    @Transactional
    public Optional<EmpresaEnPlataforma> activar(Integer empresaId, String motivo) {
        return cambiarEstado(empresaId, EstadoEmpresa.ACTIVA,
                AccionPlataforma.EMPRESA_ACTIVACION, motivo);
    }

    /**
     * RF-03. Desactivar deja la empresa en modo de consulta (RN-15): sus
     * usuarios siguen leyendo todo y dejan de escribir. No se borra ni se
     * oculta ningún registro, de modo que aquí solo cambia una columna.
     */
    @Transactional
    public Optional<EmpresaEnPlataforma> desactivar(Integer empresaId, String motivo) {
        return cambiarEstado(empresaId, EstadoEmpresa.DESACTIVADA,
                AccionPlataforma.EMPRESA_DESACTIVACION, motivo);
    }

    /**
     * El estado anterior se lee antes de escribir el nuevo, porque es lo
     * que la bitácora tiene que guardar y después del UPDATE ya no está
     * en ninguna parte.
     *
     * Pedir el estado en el que la empresa ya está no es un error: es una
     * operación que no cambia nada. Se responde con la empresa y no se
     * anota nada en la bitácora, que registra decisiones con efecto. Una
     * fila «de ACTIVA a ACTIVA» solo ensuciaría el historial que alguien
     * va a leer para entender qué le pasó a un comercio.
     */
    private Optional<EmpresaEnPlataforma> cambiarEstado(Integer empresaId, EstadoEmpresa nuevo,
                                                        AccionPlataforma accion, String motivo) {
        Optional<EstadoEmpresa> anterior = plataforma.estadoDe(empresaId);
        if (anterior.isEmpty()) {
            return Optional.empty();
        }
        if (anterior.get() == nuevo) {
            return plataforma.buscar(empresaId);
        }
        plataforma.cambiarEstado(empresaId, nuevo);
        plataforma.registrar(responsable(), empresaId, accion,
                "empresas", String.valueOf(empresaId),
                anterior.get().name(), nuevo.name(), texto(motivo));
        return plataforma.buscar(empresaId);
    }

    /**
     * Quién está haciendo esto. La bitácora exige responsable y la
     * columna no admite nulo, de modo que no hay un camino en el que esto
     * pueda fallar en silencio: si no hay nadie en el contexto, es que la
     * cadena de seguridad dejó pasar una petición sin autenticar hasta un
     * endpoint que exige rol, y eso hay que verlo entero y no como una
     * fila de bitácora sin autor.
     */
    private Integer responsable() {
        return ContextoEmpresa.actual()
                .map(UsuarioAutenticado::usuarioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Operación de plataforma sin usuario en el contexto. "
                                + "Revise la cadena de seguridad."));
    }

    /** Recorta y convierte el vacío en nulo, que es lo que la columna admite. */
    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}
