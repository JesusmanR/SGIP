package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.logica.ProductoDePlantilla;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La plantilla recorre los productos del proveedor con un cursor: cada fila se entrega
 * a quien la pidió en cuanto se lee y no se junta en una lista.
 *
 * Sin base: el {@link JdbcClient} es un doble que se responde a sí mismo en cada paso
 * de la cadena y NO sabe devolver una lista, de modo que quien pidiera `.list()` o
 * `.query(Clase)` se estrellaría. La memoria de punta a punta la mide la prueba con base.
 */
class RepositorioImportacionRecorridoTest {

    @Test
    void elRepositorioRecorreSinCargarTodo() throws Exception {
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, llamada ->
                llamada.getMethod().getReturnType().isInstance(llamada.getMock())
                        ? llamada.getMock() : null);
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        RepositorioImportacion repositorio = new RepositorioImportacion(jdbc, mock(JdbcTemplate.class));

        List<ProductoDePlantilla> vistos = new ArrayList<>();
        repositorio.recorrerProductosActivosDelProveedor(1, 3, vistos::add);

        // Un cursor de verdad: filas de una en una, sin memoria de la lista entera.
        verify(spec).withFetchSize(Integer.MIN_VALUE);
        ArgumentCaptor<RowCallbackHandler> manejador = ArgumentCaptor.forClass(RowCallbackHandler.class);
        verify(spec).query(manejador.capture());
        assertTrue(vistos.isEmpty(), "todavía no se leyó ninguna fila");

        ResultSet fila = mock(ResultSet.class);
        String[][] datos = {{"PC-001", "Portátil", "2100.00"}, {"MS-002", "Ratón", "35.50"}};
        for (int i = 0; i < datos.length; i++) {
            when(fila.getString("codigo")).thenReturn(datos[i][0]);
            when(fila.getString("nombre")).thenReturn(datos[i][1]);
            when(fila.getBigDecimal("costo")).thenReturn(new BigDecimal(datos[i][2]));
            manejador.getValue().processRow(fila);
            assertEquals(i + 1, vistos.size(), "cada fila se entrega al leerla");
        }
        assertEquals("MS-002", vistos.get(1).codigo());
        assertEquals(0, new BigDecimal("35.50").compareTo(vistos.get(1).costo()));
    }

    @Test
    void ningunMetodoDeRecorridoDevuelveUnaColeccion() {
        for (Method m : RepositorioImportacion.class.getDeclaredMethods()) {
            if (m.getName().startsWith("recorrer")) {
                Class<?> tipo = m.getReturnType();
                assertFalse(Collection.class.isAssignableFrom(tipo) || Stream.class.isAssignableFrom(tipo),
                        m.getName() + " devuelve " + tipo.getSimpleName() + ": tiene que ir fila a fila");
                assertEquals(void.class, tipo, m.getName());
            }
        }
        assertTrue(java.util.Arrays.stream(RepositorioImportacion.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("recorrerProductosActivosDelProveedor")));
    }
}
