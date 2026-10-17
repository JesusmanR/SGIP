package co.com.bspstore.sgip.config;

import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * El modo de consulta de la RN-15, en un solo punto (RF-03).
 *
 * «Un usuario de empresa desactivada obtiene 200 en toda operación de
 * lectura y 403 en toda operación de escritura». La parte difícil de esa
 * frase es «toda»: si la comprobación viviera en cada servicio, el
 * requisito se cumpliría en los que alguien se acordó de tocar y se
 * incumpliría en el siguiente que se escriba. Y se incumpliría en
 * silencio, porque una empresa desactivada que puede escribir no produce
 * ningún error: produce datos.
 *
 * Por eso está aquí, delante de todo, y por eso decide con el método
 * HTTP en lugar de con una lista de rutas. Una lista de rutas hay que
 * mantenerla; el método HTTP ya distingue leer de escribir en cada
 * endpoint que exista o se añada.
 *
 * ## A quién no afecta
 *
 * <ul>
 *   <li><b>Las peticiones sin autenticar.</b> El ingreso es un POST y
 *       tiene que seguir funcionando: un usuario de empresa desactivada
 *       entra, porque si no pudiera entrar no podría consultar nada, y
 *       consultarlo todo es justo lo que la regla le conserva.</li>
 *   <li><b>El super administrador.</b> No pertenece a ninguna empresa
 *       (RN-17), de modo que no hay estado que mirar. Además es quien
 *       reactiva: un filtro que lo bloqueara dejaría a la empresa
 *       desactivada para siempre.</li>
 * </ul>
 *
 * ## Lo que cuesta
 *
 * Una consulta de una fila por cada petición de escritura. Las lecturas
 * no consultan nada —el método se mira antes— de modo que el catálogo,
 * que es lo que más se pide, no paga nada. Se podría guardar el estado
 * en la sesión al ingresar y ahorrarse la consulta, y no se hace a
 * propósito: entonces una desactivación no surtiría efecto hasta que el
 * usuario volviera a entrar, y el super administrador que suspende un
 * comercio por falta de pago espera que deje de facturar ahora.
 */
@Component
public class FiltroEmpresaActiva extends OncePerRequestFilter {

    /**
     * Lo que no escribe. Se enumera lo seguro en lugar de enumerar lo
     * peligroso: así un método HTTP que nadie previó —PATCH llegó al
     * proyecto después que PUT— cae del lado que rechaza.
     */
    private static final Set<String> LECTURAS = Set.of(
            HttpMethod.GET.name(),
            HttpMethod.HEAD.name(),
            HttpMethod.OPTIONS.name(),
            HttpMethod.TRACE.name());

    private final RepositorioPlataforma plataforma;

    public FiltroEmpresaActiva(RepositorioPlataforma plataforma) {
        this.plataforma = plataforma;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena)
            throws ServletException, IOException {

        if (LECTURAS.contains(peticion.getMethod())) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        Optional<Integer> empresa = ContextoEmpresa.actual()
                .filter(u -> !u.esDePlataforma())
                .map(UsuarioAutenticado::empresaId);

        if (empresa.isEmpty()) {
            // Sin autenticar o usuario de plataforma: aquí no se decide.
            cadena.doFilter(peticion, respuesta);
            return;
        }

        // Falla cerrado. Si la empresa de la sesión no está en la base, no
        // es un caso que convenga dejar escribir «por si acaso»: es una
        // sesión que apunta a un comercio que no existe.
        EstadoEmpresa estado = plataforma.estadoDe(empresa.get())
                .orElse(EstadoEmpresa.DESACTIVADA);

        if (estado.permiteEscritura()) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        rechazar(respuesta);
    }

    /**
     * El cuerpo se escribe a mano y no se lanza una excepción para que la
     * recoja {@code ManejadorErrores}, porque a esta altura de la cadena
     * todavía no hay un controlador del que colgar el manejador: una
     * excepción aquí sale como un 500 del contenedor.
     *
     * El formato es el mismo ProblemDetail que el resto de la API
     * devuelve, de modo que el cliente no tiene que distinguir de dónde
     * vino el error.
     */
    private void rechazar(HttpServletResponse respuesta) throws IOException {
        respuesta.setStatus(HttpStatus.FORBIDDEN.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        respuesta.getWriter().write("""
                {"type":"about:blank",\
                "title":"Empresa en modo de consulta",\
                "status":403,\
                "detail":"La empresa está desactivada. Puedes consultar toda tu \
                información, y no registrar cambios, hasta que la plataforma la \
                reactive."}""");
    }

    /**
     * Que el despacho de error no vuelva a pasar por aquí.
     *
     * Es lo contrario de lo que hace {@link FiltroSesion}, y por un
     * motivo distinto: aquel tiene que correr en el error para que el
     * contexto de seguridad siga puesto y el fallo no se confunda con un
     * 401. Este solo decide si una escritura entra, y la escritura ya se
     * decidió antes de que hubiera un error que despachar. Volver a
     * decidirlo sobre /error solo añadiría una consulta por cada
     * petición fallida.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
