package co.com.bspstore.sgip.datos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las lecturas del calendario hábil y de la vigencia de cada empresa contra la base real. El
 * calendario es del sistema (V3, V7: festivos de 2026 y 2027); la vigencia, de cada empresa (V12).
 *
 * Crea sus empresas y las borra al terminar.
 */
@Tag("conBase")
@SpringBootTest
class CalendarioHabilConBaseTest {

    @Autowired RepositorioCalendarioHabil calendario;
    @Autowired JdbcClient jdbc;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired ProveedorRepositorio proveedores;

    private final List<EmpresaDePrueba> empresas = new ArrayList<>();

    @AfterEach
    void borrarLoQueCreo() {
        for (EmpresaDePrueba creada : empresas) {
            creada.borrar(jdbc);
        }
    }

    private EmpresaDePrueba nuevaEmpresa() {
        EmpresaDePrueba creada = EmpresaDePrueba.crear("Calendario", plataforma, jdbc, categorias, proveedores);
        empresas.add(creada);
        return creada;
    }

    @Test
    void festivosDeAbril2026() {
        Set<LocalDate> festivos = calendario.festivosEntre(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10));

        assertTrue(festivos.contains(LocalDate.of(2026, 4, 2)), "Jueves Santo: " + festivos);
        assertTrue(festivos.contains(LocalDate.of(2026, 4, 3)), "Viernes Santo: " + festivos);
    }

    @Test
    void losDosExtremosEntranYLoDeFueraNo() {
        Set<LocalDate> soloElJueves = calendario.festivosEntre(LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 2));
        Set<LocalDate> antesYDespues = calendario.festivosEntre(LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 8));

        assertEquals(Set.of(LocalDate.of(2026, 4, 2)), soloElJueves, "el extremo inferior entra y el superior también");
        assertFalse(antesYDespues.contains(LocalDate.of(2026, 4, 3)));
        assertFalse(antesYDespues.contains(LocalDate.of(2026, 4, 2)));
    }

    @Test
    void unRangoSinFestivosDaUnConjuntoVacio() {
        assertEquals(Set.of(), calendario.festivosEntre(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 8)));
    }

    @Test
    void aniosCargados() {
        assertEquals(Set.of(2026, 2027), calendario.aniosConFestivos(2026, 2028));
    }

    @Test
    void unAnioSinFestivosNoAparece() {
        assertEquals(Set.of(), calendario.aniosConFestivos(2030, 2031));
    }

    @Test
    void vigenciaPorOmision() {
        EmpresaDePrueba sinConfig = nuevaEmpresa();
        jdbc.sql("DELETE FROM empresa_config WHERE empresa_id = :e").param("e", sinConfig.empresaId).update();

        assertEquals(5, calendario.vigenciaDeLaEmpresa(sinConfig.empresaId));
    }

    @Test
    void vigenciaDeLaEmpresaLeeSuConfiguracionYNoLaDeOtra() {
        EmpresaDePrueba una = nuevaEmpresa();
        EmpresaDePrueba otra = nuevaEmpresa();
        for (EmpresaDePrueba e : List.of(una, otra)) {
            jdbc.sql("INSERT IGNORE INTO empresa_config (empresa_id) VALUES (:e)").param("e", e.empresaId).update();
        }
        jdbc.sql("UPDATE empresa_config SET cotizacion_vigencia_dias = 8 WHERE empresa_id = :e")
                .param("e", una.empresaId).update();
        jdbc.sql("UPDATE empresa_config SET cotizacion_vigencia_dias = 3 WHERE empresa_id = :e")
                .param("e", otra.empresaId).update();

        assertEquals(8, calendario.vigenciaDeLaEmpresa(una.empresaId));
        assertEquals(3, calendario.vigenciaDeLaEmpresa(otra.empresaId));
    }
}
