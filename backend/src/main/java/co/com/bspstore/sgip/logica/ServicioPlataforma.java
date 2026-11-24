package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EmpresaEnPlataforma;
import co.com.bspstore.sgip.api.dto.SolicitudAltaEmpresa;
import co.com.bspstore.sgip.api.dto.SolicitudIdentidad;
import co.com.bspstore.sgip.api.dto.UsuarioEnPlataforma;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.datos.RolRepositorio;
import co.com.bspstore.sgip.datos.UsuarioRepositorio;
import co.com.bspstore.sgip.modelo.AccionPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.IdentidadDeEmpresa;
import co.com.bspstore.sgip.modelo.Rol;
import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;
import co.com.bspstore.sgip.modelo.Usuario;
import co.com.bspstore.sgip.util.Hoy;
import co.com.bspstore.sgip.util.Seguridad;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final PoliticaDeBloqueo bloqueo;

    /**
     * {@code bloqueo} es la misma {@link PoliticaDeBloqueo} que usa
     * {@link ServicioAutenticacion}: la lista de usuarios dice «bloqueado» con
     * el mismo criterio con que el ingreso rechaza.
     */
    public ServicioPlataforma(RepositorioPlataforma plataforma,
                              UsuarioRepositorio usuarios,
                              RolRepositorio roles,
                              Clock reloj,
                              PoliticaDeBloqueo bloqueo) {
        this.plataforma = plataforma;
        this.usuarios = usuarios;
        this.roles = roles;
        this.reloj = reloj;
        this.bloqueo = bloqueo;
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
     * Los usuarios de una empresa, para elegir a quién desbloquear (ADR-0028).
     *
     * Una sola consulta: los usuarios llegan con su rol unido. Que la empresa
     * exista solo se pregunta cuando la lista viene vacía, que es el único caso
     * en que no se sabe: una empresa con usuarios existe, y en la empresa
     * inexistente la lista vacía es justo la respuesta que no se puede dar como
     * si fuera un dato.
     *
     * «Bloqueado» es lo que el ingreso diría hoy: marcado y con el plazo sin
     * vencer, medido con el mismo reloj. Leer no limpia nada; quitar la marca
     * vencida es cosa del ingreso o del desbloqueo, no de una consulta.
     */
    @Transactional(readOnly = true)
    public List<UsuarioEnPlataforma> usuariosDe(Integer empresaId) {
        List<Usuario> lista = usuarios.listarConRolDeLaEmpresa(empresaId);
        if (lista.isEmpty() && plataforma.estadoDe(empresaId).isEmpty()) {
            throw new RecursoNoEncontradoException("La empresa no existe.");
        }
        LocalDateTime ahora = LocalDateTime.now(reloj);
        return lista.stream()
                .map(u -> UsuarioEnPlataforma.de(u,
                        bloqueo.bloqueadoHoy(u, ahora)))
                .toList();
    }

    /**
     * Da de alta una empresa con su administrador inicial (RF-01).
     *
     * Las ocho escrituras —empresa, suscripción, configuración, medios
     * de pago, consumidor final, proveedor y categoría genéricos (V45), usuario y bitácora— van en una sola transacción, y no es
     * celo: a medias queda una empresa en la que nadie puede entrar, o
     * que no puede cobrar, y nadie de fuera puede crearle el primer
     * usuario, porque crear usuarios es cosa de su propio administrador
     * (RF-12). El estado irrecuperable no sería un dato perdido, sería
     * un comercio inservible que además ocupa su identificación
     * tributaria.
     *
     * Los dos choques posibles se comprueban antes de escribir para
     * poder decir cuál es. La clave única de la base sigue ahí debajo por
     * si dos altas simultáneas pasan la comprobación a la vez: entonces
     * una de las dos revienta con un 1062 y la transacción se deshace
     * entera, que es el resultado correcto aunque el mensaje sea peor.
     */
    @Transactional
    public EmpresaEnPlataforma darDeAlta(SolicitudAltaEmpresa solicitud) {
        // NIT o CC más el número, sin puntos, espacios ni guiones y sin dígito de verificación (que nunca se
        // pide). Es la misma regla del cambio de identidad, para que las dos rutas no discrepen.
        TipoDocumentoEmpresa tipoDocumento = solicitud.tipoDocumento() == null
                ? TipoDocumentoEmpresa.NIT : solicitud.tipoDocumento();
        String nit = DocumentoEmpresa.normalizar(solicitud.nit());
        String correoAdmin = solicitud.administradorCorreo().trim().toLowerCase(Locale.ROOT);

        if (plataforma.existeNit(nit)) {
            throw new NitRepetidoException(
                    "Ya hay una empresa registrada con la identificación tributaria " + nit + ".");
        }
        if (usuarios.existsByCorreo(correoAdmin)) {
            throw new CorreoRepetidoException(
                    "El correo " + correoAdmin + " ya pertenece a un usuario de la plataforma.");
        }

        Integer empresaId = plataforma.insertarEmpresa(
                tipoDocumento,
                nit,
                texto(solicitud.razonSocial()),
                texto(solicitud.correoContacto()),
                texto(solicitud.telefono()),
                texto(solicitud.ciudad()));

        LocalDate hoy = Hoy.de(reloj);
        // Treinta días calendario contados desde mañana (RF-04 de la v4,
        // ADR-0031): el alta del 31 de enero vence el 2 de marzo. El número
        // sale del parámetro vigente hoy, y fin_prueba es el último día de
        // prueba, completo.
        plataforma.insertarSuscripcion(
                empresaId,
                solicitud.plan() == null || solicitud.plan().isBlank()
                        ? PLAN_POR_OMISION : solicitud.plan().trim(),
                hoy,
                VigenciaDePrueba.finDePrueba(hoy, plataforma.diasDePrueba(hoy)));

        plataforma.insertarConfiguracion(empresaId);

        // Los medios de pago son lo único que se siembra, porque sin ellos
        // no se puede registrar ninguna venta. Los márgenes NO: los decide
        // el comercio producto por producto. Los impuestos tampoco: son
        // opcionales, y una empresa sin reglas de IVA vende al precio
        // (ADR-0024). Sembrar «valores por omisión» aquí le impondría al
        // comercio un cobro que nadie le pidió.
        plataforma.sembrarMediosDePago(empresaId, ServicioMediosPago.MEDIOS_DEL_ALTA);

        // Y el cliente genérico: la primera venta del mostrador suele ser sin
        // datos del comprador y la venta exige un cliente (RF-66).
        plataforma.sembrarConsumidorFinal(empresaId,
                ServicioClientes.NOMBRE_CONSUMIDOR_FINAL,
                ServicioClientes.TIPO_CONSUMIDOR_FINAL,
                ServicioClientes.IDENTIFICACION_CONSUMIDOR_FINAL,
                texto(solicitud.ciudad()));

        // Y el proveedor y la categoría del producto que se crea dentro de la venta: las dos
        // columnas son obligatorias y la venta solo los busca, nunca los crea a mitad de una venta.
        plataforma.sembrarSinProveedorYSinCategoria(empresaId);

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

    /**
     * Cambia la identidad de una empresa: el tipo y el número de su documento (NIT o CC) y su razón social.
     * Es lo único que el comercio no puede hacer por sí mismo (anti-objetivo del diseño: que la tienda
     * cambie su NIT), y por eso vive aquí y no en {@code ServicioEmpresa}.
     *
     * Una fila de la bitácora POR CADA campo que cambia, cada una con el NIT anterior como referencia, el
     * valor anterior y el nuevo, el motivo y el responsable, en la misma transacción que el UPDATE: o
     * quedan las dos cosas o ninguna. Si nada cambia no se escribe nada, ni en la empresa ni en la
     * bitácora, y la respuesta es la misma 200.
     *
     * «Cambió el número» se mide sobre el número normalizado, no sobre el texto: la semilla
     * {@code 900000000-0} y un {@code 9000000000} son el mismo número, y una edición que solo toca la razón
     * social no reescribe la columna del documento ni dice que lo cambió. Por la misma razón el choque con
     * otra empresa (409) excluye a la que se edita: cambiar solo la razón social no puede chocar consigo
     * misma.
     *
     * El orden de los errores es 404 (la empresa), luego 400 (el documento) y luego 409. La fila se lee con
     * bloqueo, para que dos cambios simultáneos no anoten el mismo valor anterior. Vacío si la empresa no
     * existe, para que el controlador responda 404.
     */
    @Transactional
    public Optional<EmpresaEnPlataforma> cambiarIdentidad(Integer empresaId, SolicitudIdentidad solicitud) {
        Optional<IdentidadDeEmpresa> actual = plataforma.identidadDe(empresaId);
        if (actual.isEmpty()) {
            return Optional.empty();
        }
        IdentidadDeEmpresa antes = actual.get();
        String numero = DocumentoEmpresa.normalizar(solicitud.numero());
        if (plataforma.existeNit(numero, empresaId)) {
            throw new NitRepetidoException(
                    "Ya hay otra empresa registrada con la identificación tributaria " + numero + ".");
        }

        boolean cambiaElTipo = antes.tipoDocumento() != solicitud.tipoDocumento();
        boolean cambiaElNumero = !DocumentoEmpresa.normalizadoOTalCual(antes.nit()).equals(numero);
        boolean cambiaLaRazon = !antes.razonSocial().equals(solicitud.razonSocial());
        if (!cambiaElTipo && !cambiaElNumero && !cambiaLaRazon) {
            return plataforma.buscar(empresaId);
        }

        plataforma.cambiarIdentidad(empresaId, solicitud.tipoDocumento(),
                cambiaElNumero ? numero : antes.nit(), solicitud.razonSocial());

        // La referencia es el NIT que tenía antes de este cambio, igual en las tres filas: es lo que
        // alguien busca en la bitácora para encontrar «qué le pasó a esta empresa».
        Integer responsable = responsable();
        String referencia = antes.nit();
        if (cambiaElTipo) {
            plataforma.registrar(responsable, empresaId, AccionPlataforma.CAMBIO_IDENTIDAD_EMPRESA,
                    "empresas", "tipo_documento", referencia,
                    antes.tipoDocumento().name(), solicitud.tipoDocumento().name(), solicitud.motivo());
        }
        if (cambiaElNumero) {
            plataforma.registrar(responsable, empresaId, AccionPlataforma.CAMBIO_IDENTIDAD_EMPRESA,
                    "empresas", "nit", referencia, antes.nit(), numero, solicitud.motivo());
        }
        if (cambiaLaRazon) {
            plataforma.registrar(responsable, empresaId, AccionPlataforma.CAMBIO_IDENTIDAD_EMPRESA,
                    "empresas", "razon_social", referencia,
                    antes.razonSocial(), solicitud.razonSocial(), solicitud.motivo());
        }
        return plataforma.buscar(empresaId);
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
     * Levanta el bloqueo de un usuario de la empresa indicada (ADR-0028).
     *
     * Es la salida para quien no tiene otro administrador que lo desbloquee, y
     * la única que no pasa por la propia empresa. El usuario se busca acotado
     * por la empresa de la ruta, no por su identificador suelto: uno de otra
     * empresa responde igual que uno inexistente, y el super administrador
     * tampoco puede, sin querer, tocar a quien no es de la empresa que nombró.
     *
     * Desbloquear a quien no está bloqueado no es un error: no cambia nada y no
     * se anota, por lo mismo que activar una empresa ya activa. La bitácora
     * registra decisiones con efecto.
     */
    @Transactional
    public void desbloquearUsuario(Integer empresaId, Integer usuarioId) {
        Usuario usuario = usuarios.findByIdAndEmpresaId(usuarioId, empresaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El usuario no existe."));
        if (!usuario.isBloqueado()) {
            return;
        }
        usuario.desbloquear();
        // La referencia es el identificador y no el correo: el correo mide hasta
        // 120 caracteres y la columna admite 60.
        plataforma.registrar(responsable(), empresaId, AccionPlataforma.USUARIO_DESBLOQUEO,
                "usuarios", String.valueOf(usuarioId), "BLOQUEADO", "DESBLOQUEADO", null);
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
