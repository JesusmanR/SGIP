package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.ClaveNuevaInvalidaException;
import co.com.bspstore.sgip.logica.CorreoRepetidoException;
import co.com.bspstore.sgip.logica.NitRepetidoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.CredencialesInvalidasException;
import co.com.bspstore.sgip.logica.CuentaBloqueadaException;
import co.com.bspstore.sgip.logica.EstadoDeCargaInvalidoException;
import co.com.bspstore.sgip.logica.ImpuestoNoDisponibleException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.modelo.Producto;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManejadorErroresTest {

    private final ManejadorErrores manejador = new ManejadorErrores();

    private final ListAppender<ILoggingEvent> registro = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void escucharElRegistro() {
        logger = (Logger) LoggerFactory.getLogger(ManejadorErrores.class);
        registro.start();
        logger.addAppender(registro);
    }

    @AfterEach
    void dejarDeEscuchar() {
        logger.detachAppender(registro);
    }

    private Level nivelRegistrado() {
        assertEquals(1, registro.list.size(), registro.list.toString());
        return registro.list.get(0).getLevel();
    }

    /**
     * Imita lo que entrega Hibernate: el SQLException queda dos niveles
     * por debajo de la excepción de Spring, así que getCause() a secas no
     * lo alcanza.
     */
    private static DataIntegrityViolationException delMotor(String texto, String estadoSql, int codigo) {
        return new DataIntegrityViolationException("x", new RuntimeException(
                new SQLIntegrityConstraintViolationException(texto, estadoSql, codigo)));
    }

    /** Nada de lo que escribió el motor puede llegar al cliente. */
    private static void sinTextoDelMotor(ProblemDetail p, String... fragmentos) {
        String visible = p.getTitle() + " | " + p.getDetail();
        for (String fragmento : fragmentos) {
            assertFalse(visible.contains(fragmento), visible);
        }
        assertFalse(visible.toLowerCase().contains("sql"), visible);
    }

    /** Un 401 haría que el cliente diera por expirada la sesión de quien sigue dentro. */
    @Test
    void cambiarLaClaveConLaCuentaBloqueadaEsUn423ConElPlazoYNoUn401() {
        ProblemDetail p = manejador.cuentaBloqueada(new CuentaBloqueadaException(15));

        assertEquals(423, p.getStatus());
        assertEquals("Cuenta bloqueada", p.getTitle());
        assertTrue(p.getDetail().contains("15 minutos"), p.getDetail());
    }

    @Test
    void unDuplicadoLlegaComo409SinExponerLaRestriccion() {
        var e = delMotor("Duplicate entry 'ABC' for key 'productos.uq_productos_sku'", "23000", 1062);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(409, p.getStatus());
        assertEquals("Dato ya registrado", p.getTitle());
        assertNotNull(p.getDetail());
        sinTextoDelMotor(p, "uq_productos", "productos", "Duplicate", "ABC");
    }

    @Test
    void borrarLoQueOtraFilaReferenciaEsUn409SinExponerLaTabla() {
        var e = delMotor("Cannot delete or update a parent row: a foreign key constraint fails "
                + "(`sgip`.`orden_compra_items`, CONSTRAINT `fk_oci_producto_emp` FOREIGN KEY "
                + "(`empresa_id`, `producto_id`) REFERENCES `productos` (`empresa_id`, `producto_id`))",
                "23000", 1451);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(409, p.getStatus());
        assertEquals("Registro en uso", p.getTitle());
        assertEquals("No se puede quitar o cambiar porque otros registros dependen de él.", p.getDetail());
        sinTextoDelMotor(p, "fk_oci_producto_emp", "orden_compra_items", "parent row", "sgip");
    }

    @Test
    void unDatoObligatorioAusenteEsUn400SinExponerLaColumna() {
        var e = delMotor("Column 'columna_secreta_x' cannot be null", "23000", 1048);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(400, p.getStatus());
        assertEquals("Dato obligatorio", p.getTitle());
        assertEquals("Falta un dato obligatorio.", p.getDetail());
        sinTextoDelMotor(p, "columna_secreta_x", "cannot be null");
    }

    @Test
    void unTextoMasLargoQueLaColumnaEsUn400SinExponerLaColumnaNiElValor() {
        var e = delMotor("Data too long for column 'columna_secreta_y' at row 1", "22001", 1406);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(400, p.getStatus());
        assertEquals("Dato demasiado largo", p.getTitle());
        assertEquals("Uno de los textos es más largo de lo que se admite.", p.getDetail());
        sinTextoDelMotor(p, "columna_secreta_y", "Data too long", "row 1");
    }

    /** Un choque que se esperaba no es una falla del sistema: no merece el nivel de error. */
    @Test
    void unDuplicadoNoSeRegistraComoError() {
        manejador.integridad(delMotor("Duplicate entry 'ABC' for key 'k'", "23000", 1062));

        assertTrue(nivelRegistrado().toInt() < Level.ERROR.toInt());
    }

    @Test
    void unaReferenciaInexistenteNoSeRegistraComoError() {
        manejador.integridad(delMotor("Cannot add or update a child row", "23000", 1452));

        assertTrue(nivelRegistrado().toInt() < Level.ERROR.toInt());
    }

    @Test
    void borrarAlgoReferenciadoNoSeRegistraComoError() {
        manejador.integridad(delMotor("Cannot delete or update a parent row", "23000", 1451));

        assertTrue(nivelRegistrado().toInt() < Level.ERROR.toInt());
    }

    /** Un 1048 o un 1406 que llega al motor es una validación que faltó en la solicitud: hay que verlo. */
    @Test
    void unDatoAusenteOLargoQueLlegaAlMotorQuedaComoAviso() {
        manejador.integridad(delMotor("Column 'x' cannot be null", "23000", 1048));
        manejador.integridad(delMotor("Data too long for column 'x' at row 1", "22001", 1406));

        assertEquals(2, registro.list.size());
        registro.list.forEach(ev -> assertEquals(Level.WARN, ev.getLevel()));
    }

    @Test
    void unCodigoInesperadoSigueSiendoUnError() {
        manejador.integridad(delMotor("Check constraint 'ck_x' is violated.", "HY000", 3819));

        assertEquals(Level.ERROR, nivelRegistrado());
    }

    @Test
    void sinSqlExceptionEnLaCadenaSigueSiendoUnError() {
        manejador.integridad(new DataIntegrityViolationException("algo raro"));

        assertEquals(Level.ERROR, nivelRegistrado());
    }

    @Test
    void unaReferenciaInexistenteLlegaComo422SinExponerLaTabla() {
        var e = delMotor("Cannot add or update a child row: a foreign key constraint fails "
                + "(`sgip`.`ordenes_compra`, CONSTRAINT `fk_oc_proveedor_emp` FOREIGN KEY "
                + "(`empresa_id`, `proveedor_id`) REFERENCES `proveedores` (`empresa_id`, `id`))",
                "23000", 1452);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(422, p.getStatus());
        assertEquals("Referencia no válida", p.getTitle());
        sinTextoDelMotor(p, "fk_oc_proveedor_emp", "ordenes_compra", "proveedores", "sgip", "child row");
    }

    @Test
    void cualquierOtroCodigoDelMotorEsUn500GenericoSinExponerNada() {
        var e = delMotor("Check constraint 'ck_precio_positivo' is violated.", "HY000", 3819);

        ProblemDetail p = manejador.integridad(e);

        assertEquals(500, p.getStatus());
        sinTextoDelMotor(p, "ck_precio_positivo", "Check constraint", "violated");
    }

    @Test
    void sinSqlExceptionEnLaCadenaEsUn500Generico() {
        var e = new DataIntegrityViolationException("could not execute statement [tabla_secreta]");

        ProblemDetail p = manejador.integridad(e);

        assertEquals(500, p.getStatus());
        sinTextoDelMotor(p, "tabla_secreta", "statement");
    }

    /** El 401 de ingreso orienta sin revelar: el plazo sale del valor configurado. */
    @Test
    void elRechazoDelIngresoDiceCuantoDuraElBloqueoSegunLoConfigurado() {
        ProblemDetail p = manejador.credencialesInvalidas(new CredencialesInvalidasException(20));

        assertEquals(401, p.getStatus());
        assertEquals("Credenciales inválidas", p.getTitle());
        assertEquals("El correo o la contraseña no son correctos. Si te equivocas varias veces, "
                + "la cuenta se bloquea durante 20 minutos.", p.getDetail());
    }

    @Test
    void elPlazoDeUnMinutoSeDiceEnSingular() {
        ProblemDetail p = manejador.credencialesInvalidas(new CredencialesInvalidasException(1));

        assertTrue(p.getDetail().endsWith("durante 1 minuto."), p.getDetail());
    }

    @Test
    void unRecursoQueNoEsDeLaEmpresaEsUn404() {
        ProblemDetail p = manejador.recursoNoEncontrado(
                new RecursoNoEncontradoException("La imagen indicada no existe en esta empresa."));

        assertEquals(404, p.getStatus());
        assertEquals("La imagen indicada no existe en esta empresa.", p.getDetail());
    }

    @Test
    void unaCargaEnOtroEstadoEsUn409() {
        ProblemDetail p = manejador.estadoDeCargaInvalido(
                new EstadoDeCargaInvalidoException("La carga 4 ya está aplicada."));

        assertEquals(409, p.getStatus());
        assertEquals("La carga no admite esa operación", p.getTitle());
        assertEquals("La carga 4 ya está aplicada.", p.getDetail());
    }

    @Test
    void unaEdicionQueLlegaTardeEsUn409SinExponerNingunNombreInterno() {
        // El mensaje de Hibernate nombra la clase y el identificador.
        var e = new ObjectOptimisticLockingFailureException(Producto.class, 42);

        ProblemDetail p = manejador.edicionConcurrente(e);

        assertEquals(409, p.getStatus());
        assertEquals("El producto cambió mientras lo editabas", p.getTitle());
        assertNotNull(p.getDetail());
        sinTextoDelMotor(p, "co.com", "bspstore", "Producto.class", "42", "version", "Row was");
    }

    /**
     * El 409 tiene varias causas (nombre repetido, carga en otro estado, dato
     * ya registrado) y el cliente no puede deducir cuál mirando el estado. Este
     * lleva un tipo propio: es el único que justifica ofrecer «ver los datos
     * actuales» en el formulario de edición (I1).
     */
    @Test
    void laEdicionQueLlegaTardeSeDistingueDeOtrosConflictosPorSuTipo() {
        ProblemDetail tarde = manejador.edicionConcurrente(
                new ObjectOptimisticLockingFailureException(Producto.class, 42));
        ProblemDetail repetido = manejador.nombreRepetido(
                new co.com.bspstore.sgip.logica.NombreRepetidoException("Ya existe un producto con el código X."));

        assertEquals(ManejadorErrores.TIPO_PRODUCTO_CAMBIO, tarde.getType());
        assertEquals("urn:sgip:producto-cambio", tarde.getType().toString());
        assertNotEquals(ManejadorErrores.TIPO_PRODUCTO_CAMBIO, repetido.getType());
    }

    /**
     * El cliente trata todo 401 como «la sesión expiró» y borra la sesión.
     * Un error al teclear la contraseña no puede echar al usuario.
     */
    @Test
    void losErroresDeContrasenaSonDe400YNuncaDe401() {
        ProblemDetail actual = manejador.claveActualIncorrecta(new ClaveActualIncorrectaException());
        ProblemDetail nueva = manejador.claveNuevaInvalida(
                new ClaveNuevaInvalidaException("La contraseña nueva debe ser distinta de la actual."));

        assertEquals(400, actual.getStatus());
        assertEquals(400, nueva.getStatus());
        assertEquals("La contraseña actual no es correcta.", actual.getDetail());
        assertEquals("La contraseña nueva debe ser distinta de la actual.", nueva.getDetail());
    }

    /**
     * El 400 de clave actual incorrecta y los 400 del DTO se parecen en el estado;
     * el cliente lo reconoce por el tipo y no por el principio del texto.
     */
    @Test
    void laClaveActualIncorrectaLlevaSuPropioTipo() {
        ProblemDetail actual = manejador.claveActualIncorrecta(new ClaveActualIncorrectaException());
        ProblemDetail nueva = manejador.claveNuevaInvalida(
                new ClaveNuevaInvalidaException("La contraseña nueva debe ser distinta de la actual."));

        assertEquals("urn:sgip:clave-actual-incorrecta", actual.getType().toString());
        assertEquals(ManejadorErrores.TIPO_CLAVE_ACTUAL_INCORRECTA, actual.getType());
        assertNotEquals(ManejadorErrores.TIPO_CLAVE_ACTUAL_INCORRECTA, nueva.getType());
    }

    @Test
    void elNitRepetidoLlevaSuPropioTipoYConservaElDetalle() {
        ProblemDetail p = manejador.nitRepetido(new NitRepetidoException(
                "Ya hay una empresa registrada con la identificación tributaria 900."));

        assertEquals(409, p.getStatus());
        assertEquals("Dato ya registrado", p.getTitle());
        assertEquals("urn:sgip:nit-repetido", p.getType().toString());
        assertEquals("Ya hay una empresa registrada con la identificación tributaria 900.", p.getDetail());
    }

    @Test
    void elCorreoRepetidoLlevaSuPropioTipoYConservaElDetalle() {
        ProblemDetail p = manejador.correoRepetido(new CorreoRepetidoException(
                "El correo a@b.co ya pertenece a un usuario de la plataforma."));

        assertEquals(409, p.getStatus());
        assertEquals("Dato ya registrado", p.getTitle());
        assertEquals("urn:sgip:correo-repetido", p.getType().toString());
        assertEquals("El correo a@b.co ya pertenece a un usuario de la plataforma.", p.getDetail());
    }

    /** Los demás datos repetidos (reglas, órdenes) siguen sin tipo propio. */
    @Test
    void otroRegistroRepetidoNoLlevaTipoPropio() {
        ProblemDetail p = manejador.registroRepetido(new RegistroRepetidoException("Se solapa con otra regla."));

        assertEquals(409, p.getStatus());
        assertTrue(p.getType() == null || "about:blank".equals(p.getType().toString()), String.valueOf(p.getType()));
    }

    @Test
    void unImpuestoQueNoSePuedeUsarEsUn422ConSuPropioTitulo() {
        ProblemDetail p = manejador.impuestoNoDisponible(
                new ImpuestoNoDisponibleException("El impuesto «X» no existe o no está activo."));

        // 422 como la referencia ajena, pero con otro título: el de aquella
        // habla de «otra empresa», y el catálogo de impuestos no es de ninguna.
        assertEquals(422, p.getStatus());
        assertEquals("Impuesto no disponible", p.getTitle());
        assertEquals("El impuesto «X» no existe o no está activo.", p.getDetail());
    }
}
