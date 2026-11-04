package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.logica.ClaveActualIncorrectaException;
import co.com.bspstore.sgip.logica.ClaveNuevaInvalidaException;
import co.com.bspstore.sgip.logica.EstadoDeCargaInvalidoException;
import co.com.bspstore.sgip.logica.ImpuestoNoDisponibleException;
import co.com.bspstore.sgip.logica.RecursoNoEncontradoException;
import co.com.bspstore.sgip.modelo.Producto;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ManejadorErroresTest {

    private final ManejadorErrores manejador = new ManejadorErrores();

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
