package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.modelo.AccionPlataforma;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las dos guardas de la bitácora, que es lo único de este repositorio
 * que se puede probar sin una base.
 *
 * El cliente de JDBC se simula y no se usa: la gracia de estas pruebas
 * es justamente que la fila inválida no llega a la sentencia. Si algún
 * día la guarda se mueve después del INSERT, estas pruebas fallan al
 * verificar que nadie tocó el cliente.
 */
@ExtendWith(MockitoExtension.class)
class RepositorioPlataformaTest {

    @Mock JdbcClient jdbc;

    private RepositorioPlataforma repositorio() {
        return new RepositorioPlataforma(jdbc);
    }

    @Test
    void unaAccionDeEmpresaSinEmpresaNoLlegaALaSentencia() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repositorio().registrar(1, null, AccionPlataforma.EMPRESA_ACTIVACION,
                        "empresas", "5", "PRUEBA", "ACTIVA", null));

        assertTrue(e.getMessage().contains("llegó sin empresa"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unaAccionNacionalAtribuidaAUnaEmpresaNoLlegaALaSentencia() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repositorio().registrar(1, 3, AccionPlataforma.UVT_CAMBIO,
                        "uvt_anual", "2026", "52374.00", "55000.00", null));

        assertTrue(e.getMessage().contains("alcance nacional"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unCambioSinValorAnteriorNoLlegaALaSentencia() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> repositorio().registrar(1, 5, AccionPlataforma.EMPRESA_DESACTIVACION,
                        "empresas", "5", null, "DESACTIVADA", null));

        assertTrue(e.getMessage().contains("sin valor anterior"));
        verifyNoInteractions(jdbc);
    }

    /**
     * El reparto de acciones en los dos grupos tiene que coincidir con el
     * de las restricciones de la V21. Esta prueba es la que avisa si
     * alguien añade una acción al ENUM y la clasifica mal: una acción de
     * empresa que además sea nacional, o un alta que exija valor
     * anterior, son combinaciones que la tabla rechazaría siempre.
     */
    @Test
    void elRepartoDeAccionesCoincideConLasRestriccionesDeLaTabla() {
        assertAll(
                () -> assertTrue(AccionPlataforma.EMPRESA_ALTA.esDeEmpresa()),
                () -> assertTrue(AccionPlataforma.EMPRESA_ACTIVACION.esDeEmpresa()),
                () -> assertTrue(AccionPlataforma.EMPRESA_DESACTIVACION.esDeEmpresa()),
                () -> assertFalse(AccionPlataforma.UVT_ALTA.esDeEmpresa()),
                () -> assertFalse(AccionPlataforma.UVT_CAMBIO.esDeEmpresa()),
                () -> assertFalse(AccionPlataforma.IMPUESTO_ALTA.esDeEmpresa()),
                () -> assertFalse(AccionPlataforma.IMPUESTO_CAMBIO.esDeEmpresa()),
                // Un alta no tiene valor anterior; un cambio sí.
                () -> assertFalse(AccionPlataforma.EMPRESA_ALTA.esCambio()),
                () -> assertFalse(AccionPlataforma.UVT_ALTA.esCambio()),
                () -> assertFalse(AccionPlataforma.IMPUESTO_ALTA.esCambio()),
                () -> assertTrue(AccionPlataforma.EMPRESA_ACTIVACION.esCambio()),
                () -> assertTrue(AccionPlataforma.EMPRESA_DESACTIVACION.esCambio()),
                () -> assertTrue(AccionPlataforma.UVT_CAMBIO.esCambio()),
                () -> assertTrue(AccionPlataforma.IMPUESTO_CAMBIO.esCambio()));
    }
}
