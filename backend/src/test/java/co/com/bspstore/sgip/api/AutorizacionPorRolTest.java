package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.CuadreDeInventario;
import co.com.bspstore.sgip.api.dto.ProductoEnCatalogo;
import co.com.bspstore.sgip.config.ConfiguracionSeguridad;
import co.com.bspstore.sgip.config.FiltroCambioClave;
import co.com.bspstore.sgip.config.FiltroEmpresaActiva;
import co.com.bspstore.sgip.config.FiltroSesion;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.RepositorioPlataforma;
import co.com.bspstore.sgip.logica.ServicioAutenticacion;
import co.com.bspstore.sgip.logica.ServicioInventario;
import co.com.bspstore.sgip.logica.ServicioProducto;
import co.com.bspstore.sgip.logica.ServicioProveedor;
import co.com.bspstore.sgip.logica.ServicioSesion;
import co.com.bspstore.sgip.modelo.EstadoEmpresa;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién ve el costo, los proveedores y el kárdex (arreglo 7).
 *
 * Pasa por la cadena de seguridad real —los tres filtros y la anotación
 * {@code @PreAuthorize}—, no por los controladores sueltos: una prueba que
 * llamara al método directamente no vería nunca un 403.
 *
 * Qué prueban las pruebas de la seguridad de métodos: la empresa de la
 * sesión está ACTIVA (ver {@link #empresaActiva}), de modo que
 * {@code FiltroEmpresaActiva} deja pasar las escrituras y un 403 en un
 * {@code POST} o {@code PUT} de productos solo puede venir de la anotación.
 * Si se quita {@code @EnableMethodSecurity} esas pruebas se ponen en rojo,
 * porque el servicio se llega a invocar. Las denegaciones salen como
 * {@code ProblemDetail} con un texto fijo en español, no con el mensaje de
 * la excepción (el de Spring es «Access Denied»).
 */
@WebMvcTest(controllers = {ProductoControlador.class, ProveedorControlador.class,
        InventarioControlador.class, AutenticacionControlador.class})
@Import({ConfiguracionSeguridad.class, FiltroSesion.class, FiltroCambioClave.class,
        FiltroEmpresaActiva.class, ManejadorErrores.class})
class AutorizacionPorRolTest {

    @Autowired MockMvc mvc;

    @MockitoBean ServicioProducto productos;
    @MockitoBean ServicioProveedor proveedores;
    @MockitoBean ServicioInventario inventario;
    @MockitoBean ServicioAutenticacion autenticacion;
    // Dependencias de los filtros: con ellas simuladas, los filtros son los
    // de verdad y lo único que se finge es la base de datos.
    @MockitoBean ServicioSesion sesiones;
    @MockitoBean RepositorioPlataforma plataforma;

    private static final String TITULO_SIN_PERMISO = "Sin permiso para esta operación";
    private static final String DETALLE_SIN_PERMISO = "Tu rol no tiene permiso para hacer esto.";

    @BeforeEach
    void empresaActiva() {
        // Sin esto el mock devuelve vacío y FiltroEmpresaActiva (que falla
        // cerrado) respondería 403 a toda escritura antes del controlador.
        when(plataforma.estadoDe(4)).thenReturn(Optional.of(EstadoEmpresa.ACTIVA));
    }

    private static RequestPostProcessor como(String rol) {
        return como(new UsuarioAutenticado(7, "ana@tienda.co", rol, 4));
    }

    private static RequestPostProcessor como(UsuarioAutenticado u) {
        var autoridades = u.rol() == null
                ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + u.rol()));
        return authentication(new UsernamePasswordAuthenticationToken(u, null, autoridades));
    }

    private static ProductoEnCatalogo producto() {
        return new ProductoEnCatalogo(9, "ARZ-500", "ARZ-500", "Arroz Diana", "500 g", null,
                "7701", 4, "Granos", 3, "Distribuidora", new BigDecimal("3100.00"), 12, 5,
                "ACTIVO", new BigDecimal("3720.00"), new BigDecimal("3875.00"), "EXCLUIDO",
                BigDecimal.ZERO, new BigDecimal("3720.00"), new BigDecimal("3875.00"),
                "SOBRE_COSTO", "SOBRE_PRECIO", 0, 0, 0, 0, null, 0,
                new BigDecimal("20.00"), new BigDecimal("25.00"), new BigDecimal("3720"), null,
                null, new BigDecimal("25.00"));
    }

    /** El 403 de la API: ProblemDetail con texto fijo, no el cuerpo vacío del contenedor. */
    private static ResultActions esDenegado(ResultActions r) throws Exception {
        return r.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value(TITULO_SIN_PERMISO))
                .andExpect(jsonPath("$.detail").value(DETALLE_SIN_PERMISO));
    }

    private void hayProducto() {
        when(productos.listar(any(), any(), any(), any(), any())).thenReturn(List.of(producto()));
        when(productos.buscar(9)).thenReturn(Optional.of(producto()));
        when(productos.porCodigoBarras("7701")).thenReturn(Optional.of(producto()));
    }

    // ---- lo que reciben quienes no son administrador ------------------

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void quienNoEsAdministradorVeElListadoSinCostoNiProveedor(String rol) throws Exception {
        hayProducto();

        mvc.perform(get("/api/v1/productos").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].precioContado").exists())
                .andExpect(jsonPath("$[0].existencia").exists())
                .andExpect(jsonPath("$[0].baseContado").exists())
                .andExpect(jsonPath("$[0].costo").doesNotExist())
                .andExpect(jsonPath("$[0].proveedor").doesNotExist())
                .andExpect(jsonPath("$[0].proveedorId").doesNotExist())
                .andExpect(jsonPath("$[0].margenContado").doesNotExist())
                .andExpect(jsonPath("$[0].margenCredito").doesNotExist())
                .andExpect(jsonPath("$[0].precioFijoContado").doesNotExist())
                .andExpect(jsonPath("$[0].precioFijoCredito").doesNotExist())
                .andExpect(jsonPath("$[0].porcentajeContado").doesNotExist())
                .andExpect(jsonPath("$[0].porcentajeCredito").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void quienNoEsAdministradorVeUnProductoSinCostoNiProveedor(String rol) throws Exception {
        hayProducto();

        mvc.perform(get("/api/v1/productos/9").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioContado").exists())
                .andExpect(jsonPath("$.existencia").exists())
                .andExpect(jsonPath("$.baseContado").exists())
                .andExpect(jsonPath("$.costo").doesNotExist())
                .andExpect(jsonPath("$.proveedor").doesNotExist())
                .andExpect(jsonPath("$.proveedorId").doesNotExist())
                .andExpect(jsonPath("$.margenContado").doesNotExist())
                .andExpect(jsonPath("$.margenCredito").doesNotExist())
                .andExpect(jsonPath("$.precioFijoContado").doesNotExist())
                .andExpect(jsonPath("$.precioFijoCredito").doesNotExist())
                .andExpect(jsonPath("$.porcentajeContado").doesNotExist())
                .andExpect(jsonPath("$.porcentajeCredito").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void quienNoEsAdministradorEscaneaUnProductoSinCostoNiProveedor(String rol) throws Exception {
        hayProducto();

        mvc.perform(get("/api/v1/productos/barras/7701").with(como(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioContado").exists())
                .andExpect(jsonPath("$.existencia").exists())
                .andExpect(jsonPath("$.baseContado").exists())
                .andExpect(jsonPath("$.costo").doesNotExist())
                .andExpect(jsonPath("$.proveedor").doesNotExist())
                .andExpect(jsonPath("$.proveedorId").doesNotExist())
                .andExpect(jsonPath("$.margenContado").doesNotExist())
                .andExpect(jsonPath("$.margenCredito").doesNotExist())
                .andExpect(jsonPath("$.precioFijoContado").doesNotExist())
                .andExpect(jsonPath("$.precioFijoCredito").doesNotExist())
                .andExpect(jsonPath("$.porcentajeContado").doesNotExist())
                .andExpect(jsonPath("$.porcentajeCredito").doesNotExist());
    }

    // ---- lo que se les niega -----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void filtrarPorProveedorSeNiegaAQuienNoEsAdministrador(String rol) throws Exception {
        hayProducto();

        esDenegado(mvc.perform(get("/api/v1/productos").param("proveedor", "9").with(como(rol))));
        verifyNoInteractions(productos);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VENDEDOR", "CLIENTE", "CONTADOR"})
    @NullSource
    void proveedoresYKardexSonSoloDelAdministrador(String rol) throws Exception {
        when(inventario.cuadre(1)).thenReturn(Optional.of(new CuadreDeInventario(1, 0, 0, true)));

        for (String ruta : List.of("/api/v1/proveedores", "/api/v1/proveedores/9",
                "/api/v1/inventario/movimientos",
                "/api/v1/inventario/productos/1/movimientos",
                "/api/v1/inventario/productos/1/cuadre")) {
            esDenegado(mvc.perform(get(ruta).with(como(rol))));
        }
        verifyNoInteractions(proveedores);
    }

    @Test
    void sinAutenticarTodoEsUn401() throws Exception {
        for (String ruta : List.of("/api/v1/productos", "/api/v1/productos/9",
                "/api/v1/productos/barras/7701", "/api/v1/proveedores",
                "/api/v1/proveedores/9", "/api/v1/inventario/movimientos",
                "/api/v1/inventario/productos/1/movimientos",
                "/api/v1/inventario/productos/1/cuadre")) {
            mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
        }
    }

    // ---- el administrador lo conserva todo ---------------------------

    @Test
    void elAdministradorSigueViendoCostoYProveedor() throws Exception {
        hayProducto();

        for (String ruta : List.of("/api/v1/productos", "/api/v1/productos/9",
                "/api/v1/productos/barras/7701")) {
            String raiz = ruta.equals("/api/v1/productos") ? "$[0]" : "$";
            mvc.perform(get(ruta).with(como("ADMINISTRADOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(raiz + ".costo").value(3100.00))
                    .andExpect(jsonPath(raiz + ".proveedor").value("Distribuidora"))
                    .andExpect(jsonPath(raiz + ".proveedorId").value(3))
                    .andExpect(jsonPath(raiz + ".baseContado").exists())
                    .andExpect(jsonPath(raiz + ".margenContado").value(20.00))
                    .andExpect(jsonPath(raiz + ".precioFijoContado").value(3720))
                    .andExpect(jsonPath(raiz + ".porcentajeCredito").value(25.00));
        }
    }

    @Test
    void elAdministradorPuedeFiltrarPorProveedorYConsultarProveedoresYKardex() throws Exception {
        hayProducto();
        when(inventario.cuadre(1)).thenReturn(Optional.of(new CuadreDeInventario(1, 0, 0, true)));

        mvc.perform(get("/api/v1/productos").param("proveedor", "9").with(como("ADMINISTRADOR")))
                .andExpect(status().isOk());
        for (String ruta : List.of("/api/v1/proveedores", "/api/v1/inventario/movimientos",
                "/api/v1/inventario/productos/1/movimientos",
                "/api/v1/inventario/productos/1/cuadre")) {
            mvc.perform(get(ruta).with(como("ADMINISTRADOR"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/proveedores/9").with(como("ADMINISTRADOR")))
                .andExpect(status().isNotFound());
    }

    // ---- la seguridad de métodos está activa en esta prueba ----------

    private static final String CUERPO_PRODUCTO = """
            {"categoriaId":4,"proveedorId":3,"codigo":"ARZ-500","nombre":"Arroz Diana",
             "costo":3100.00}""";

    @Test
    void sinPermisoNoSePuedeCrearUnProducto() throws Exception {
        esDenegado(mvc.perform(post("/api/v1/productos").with(como("VENDEDOR"))
                .contentType(MediaType.APPLICATION_JSON).content(CUERPO_PRODUCTO)));

        verifyNoInteractions(productos);
    }

    @Test
    void sinPermisoNoSePuedeEditarUnProducto() throws Exception {
        esDenegado(mvc.perform(put("/api/v1/productos/9").with(como("VENDEDOR"))
                .contentType(MediaType.APPLICATION_JSON).content(CUERPO_PRODUCTO)));

        verifyNoInteractions(productos);
    }

    @Test
    void elAdministradorSiLlegaAlServicioAlCrearUnProducto() throws Exception {
        var creado = mock(Producto.class);
        when(creado.getId()).thenReturn(9);
        when(productos.crear(any())).thenReturn(creado);
        when(productos.buscar(9)).thenReturn(Optional.of(producto()));

        mvc.perform(post("/api/v1/productos").with(como("ADMINISTRADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO_PRODUCTO))
                .andExpect(status().isCreated());

        verify(productos).crear(any());
    }

    // ---- la puerta del cambio de contraseña, con la cadena real -------

    @Test
    void conElCambioDeClavePendienteElCatalogoSeCierra() throws Exception {
        hayProducto();
        var pendiente = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", 4, true);

        mvc.perform(get("/api/v1/productos").with(como(pendiente)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CAMBIO_CLAVE_REQUERIDO"));
    }

    @Test
    void conElCambioDeClavePendienteCambiarLaClaveSigueAbierto() throws Exception {
        var pendiente = new UsuarioAutenticado(7, "ana@tienda.co", "VENDEDOR", 4, true);
        String cuerpo = "{\"claveActual\":\"inicial-123\",\"claveNueva\":\"nueva-12345\"}";

        mvc.perform(post("/api/v1/auth/clave").with(como(pendiente))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isNoContent());
    }
}
