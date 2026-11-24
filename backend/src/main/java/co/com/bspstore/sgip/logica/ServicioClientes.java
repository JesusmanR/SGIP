package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.Cliente;
import co.com.bspstore.sgip.api.dto.ClienteEnLista;
import co.com.bspstore.sgip.api.dto.PaginaDeClientes;
import co.com.bspstore.sgip.api.dto.SolicitudCliente;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.RepositorioClientes;
import co.com.bspstore.sgip.modelo.TipoDocumento;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Los clientes de la caja (RF-66).
 *
 * La empresa sale del contexto y no de un parámetro (RN-16). Cada empresa tiene un
 * «Consumidor final» (CC 222222222222, el documento genérico de la DIAN) para las
 * ventas sin datos del comprador: lo siembran el alta y la V41, lo gestiona el
 * sistema y no se edita. Ningún mensaje repite el dato que el cliente envió.
 */
@Service
public class ServicioClientes {

    public static final String NOMBRE_CONSUMIDOR_FINAL = "Consumidor final";
    public static final TipoDocumento TIPO_CONSUMIDOR_FINAL = TipoDocumento.CC;
    public static final String IDENTIFICACION_CONSUMIDOR_FINAL = "222222222222";

    /** Por dónde se obtuvo el consentimiento: aquí, en el mostrador (Ley 1581, RNF-18). */
    static final String MEDIO_AUTORIZACION = "CAJA";

    /** Lo más que cabe en una página de la lista; un tamaño mayor se recorta a esto. */
    public static final int TAMANO_MAXIMO = 100;

    /** Sin cliente real: al crear, ningún identificador coincide con este. */
    private static final int NINGUN_CLIENTE = 0;

    private final RepositorioClientes clientes;
    private final Clock reloj;

    public ServicioClientes(RepositorioClientes clientes, Clock reloj) {
        this.clientes = clientes;
        this.reloj = reloj;
    }

    /** Vacío si la empresa no tiene un cliente con ese documento. */
    @Transactional(readOnly = true)
    public Optional<Cliente> porIdentificacion(TipoDocumento tipo, String identificacion) {
        String buscada = texto(identificacion);
        if (buscada == null) {
            throw new FiltroInvalidoException("La identificación es obligatoria.");
        }
        return clientes.buscarPorIdentificacion(ContextoEmpresa.empresaActual(), tipo, buscada);
    }

    /**
     * Una página de los clientes de la empresa, por nombre, con lo que cada uno tiene a favor.
     *
     * {@code pagina} empieza en 0 y {@code tamano} en 1; fuera de eso es un 400. Un tamaño mayor que
     * {@link #TAMANO_MAXIMO} se recorta a ese máximo. {@code buscar} se recorta y se toma al pie de la
     * letra, sin comodines: el documento debe empezar por el texto o el nombre debe contenerlo, sin
     * distinguir mayúsculas.
     */
    @Transactional(readOnly = true)
    public PaginaDeClientes listar(String buscar, int pagina, int tamano) {
        if (pagina < 0) {
            throw new FiltroInvalidoException("La página no puede ser negativa.");
        }
        if (tamano < 1) {
            throw new FiltroInvalidoException("El tamaño de la página debe ser al menos 1.");
        }
        int aplicado = Math.min(tamano, TAMANO_MAXIMO);
        Integer empresa = ContextoEmpresa.empresaActual();
        String texto = texto(buscar);
        String prefijo = null;
        String contiene = null;
        if (texto != null) {
            String literal = escaparComodines(texto);
            prefijo = literal + "%";
            contiene = "%" + literal + "%";
        }
        List<ClienteEnLista> elementos = clientes.listar(empresa, prefijo, contiene, aplicado,
                (long) pagina * aplicado);
        return new PaginaDeClientes(elementos, pagina, aplicado, clientes.contar(empresa, prefijo, contiene));
    }

    /** La barra invertida, el porcentaje y el guion bajo valen por sí mismos en un LIKE: se les antepone la barra. */
    private static String escaparComodines(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional
    public Cliente crear(SolicitudCliente solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Cliente datos = validar(empresa, solicitud, NINGUN_CLIENTE);
        Integer id = clientes.insertar(empresa, datos, LocalDateTime.now(reloj), MEDIO_AUTORIZACION);
        return releer(empresa, id);
    }

    /**
     * Edita un cliente de la empresa. Uno de otra empresa responde igual que uno
     * inexistente (404) y el consumidor final no se edita (409). Si el cliente ya
     * había autorizado el tratamiento de sus datos, el repositorio conserva el
     * instante original.
     */
    @Transactional
    public Cliente actualizar(Integer id, SolicitudCliente solicitud) {
        Integer empresa = ContextoEmpresa.empresaActual();
        Cliente actual = clientes.buscar(empresa, id)
                .orElseThrow(() -> new RecursoNoEncontradoException("El cliente no existe."));
        if (actual.consumidorFinal()) {
            throw new ClienteNoEditableException(
                    "El Consumidor final lo gestiona el sistema y no se puede editar.");
        }
        Cliente datos = validar(empresa, solicitud, id);
        clientes.actualizar(empresa, id, datos, LocalDateTime.now(reloj), MEDIO_AUTORIZACION);
        return releer(empresa, id);
    }

    /**
     * El cliente genérico de la empresa. Que falte no es un dato del usuario sino
     * una empresa mal sembrada (ni el alta ni la V41 lo dejaron): error 500 a la
     * vista y no una venta sin cliente.
     */
    @Transactional(readOnly = true)
    public Cliente consumidorFinal() {
        Integer empresa = ContextoEmpresa.empresaActual();
        return clientes.buscarConsumidorFinal(empresa).orElseThrow(() -> new IllegalStateException(
                "La empresa " + empresa + " no tiene su Consumidor final: ni el alta ni la V41 lo sembraron."));
    }

    /**
     * Un domicilio se entrega a alguien: pide los datos mínimos del cliente (nombre, identificación y
     * correo) y, además, teléfono, dirección y ciudad (RF-66), y nunca sirve el consumidor final, que
     * no identifica a nadie. El correo ya lo exige el alta de todo cliente que se guarda, pero un
     * cliente anterior a esa regla puede no tenerlo. El mensaje nombra lo que falta y no el valor de nada.
     */
    public void exigirDatosDeDomicilio(Cliente cliente) {
        if (cliente.consumidorFinal()) {
            throw new DatosDeDomicilioIncompletosException(
                    "El Consumidor final no sirve para un domicilio: hace falta un cliente con sus datos.");
        }
        List<String> faltan = new ArrayList<>();
        if (texto(cliente.nombre()) == null) {
            faltan.add("nombre");
        }
        if (texto(cliente.identificacion()) == null) {
            faltan.add("identificación");
        }
        if (texto(cliente.correo()) == null) {
            faltan.add("correo");
        }
        if (texto(cliente.telefono()) == null) {
            faltan.add("teléfono");
        }
        if (texto(cliente.direccion()) == null) {
            faltan.add("dirección");
        }
        if (texto(cliente.ciudad()) == null) {
            faltan.add("ciudad");
        }
        if (!faltan.isEmpty()) {
            throw new DatosDeDomicilioIncompletosException(
                    "Para un domicilio faltan datos del cliente: " + String.join(", ", faltan) + ".");
        }
    }

    /**
     * Las reglas que comparten el alta y la edición. Devuelve los datos ya limpios,
     * con la ciudad resuelta. {@code excluirId} es el cliente que se edita, para que
     * no choque consigo mismo.
     */
    private Cliente validar(Integer empresa, SolicitudCliente s, int excluirId) {
        if (!s.autorizacionDatos()) {
            throw new DatoDeClienteInvalidoException(
                    "Hace falta la autorización del cliente para tratar sus datos personales.");
        }
        String ciudad = texto(s.ciudad());
        if (ciudad == null) {
            ciudad = clientes.ciudadDeLaEmpresa(empresa).map(ServicioClientes::texto).orElse(null);
        }
        if (ciudad == null) {
            throw new DatoDeClienteInvalidoException(
                    "La ciudad es obligatoria: el cliente no la trae y la empresa no tiene una por omisión.");
        }
        String identificacion = texto(s.identificacion());
        if (clientes.existeOtroConLaIdentificacion(empresa, s.tipoDocumento(), identificacion, excluirId)) {
            throw new RegistroRepetidoException("Ya existe un cliente con ese tipo y número de documento.");
        }
        return new Cliente(null, texto(s.nombre()), s.tipoDocumento(), identificacion, texto(s.correo()),
                texto(s.telefono()), texto(s.direccion()), ciudad, false);
    }

    private Cliente releer(Integer empresa, Integer id) {
        return clientes.buscar(empresa, id).orElseThrow(() -> new IllegalStateException(
                "El cliente " + id + " no se encontró justo después de guardarlo."));
    }

    /** Recorta y convierte el vacío en nulo, que es lo que las columnas admiten. */
    private static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}
