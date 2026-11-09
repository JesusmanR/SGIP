package co.com.bspstore.sgip.datos;

import co.com.bspstore.sgip.api.dto.ConfiguracionTributaria;
import co.com.bspstore.sgip.modelo.Categoria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El SQL de {@link RepositorioConfiguracionTributaria} contra una base de
 * verdad.
 *
 * Las pruebas del servicio simulan este repositorio, y
 * {@code verificar_sql_del_backend.py} solo comprueba que las sentencias se
 * puedan analizar. Aquí se ejecutan: el upsert de la configuración, el
 * solape con la vigencia abierta y los parámetros nulos que
 * {@code JdbcClient} entrega al motor, el cierre y el retiro de reglas, y
 * que lo de una empresa no toque a la otra.
 *
 * Necesita la base con el esquema aplicado: se excluye sin ella con
 * {@code -DexcludedGroups=conBase}. Crea dos empresas propias y las borra al
 * terminar, de modo que no depende de los datos de demostración ni del
 * orden de las pruebas.
 */
@Tag("conBase")
@SpringBootTest
class RepositorioConfiguracionTributariaTest {

    private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
    /** El «hoy» que el servicio pasa a las consultas; fijo, para no depender del día de la base. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 3);
    /** Futura para el «hoy» de la prueba y para el día real del motor, cuando sea que corra. */
    private static final LocalDate LEJANA = LocalDate.of(2099, 1, 1);

    @Autowired RepositorioConfiguracionTributaria repositorio;
    @Autowired RepositorioPlataforma plataforma;
    @Autowired CategoriaRepositorio categorias;
    @Autowired JdbcClient jdbc;

    private Integer empresa;
    private Integer otra;
    private Integer categoria;
    private Integer tipoIva19;

    @BeforeEach
    void prepararDatosPropios() {
        String marca = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        empresa = plataforma.insertarEmpresa("T" + marca, "Tributaria A " + marca, null, null, null);
        otra = plataforma.insertarEmpresa("U" + marca, "Tributaria B " + marca, null, null, null);
        categoria = categorias.save(new Categoria(empresa, "Prueba", null)).getId();
        tipoIva19 = jdbc.sql("SELECT tipo_impuesto_id FROM tipos_impuesto WHERE codigo = 'IVA_19'")
                .query(Integer.class).single();
    }

    @AfterEach
    void borrarLoQueCreo() {
        // De las hojas hacia la raíz: las claves compuestas por empresa
        // impiden borrar un padre con hijos.
        for (Integer e : new Integer[] {empresa, otra}) {
            if (e == null) {
                continue;
            }
            for (String tabla : List.of("reglas_iva", "empresa_config", "categorias", "empresas")) {
                jdbc.sql("DELETE FROM " + tabla + " WHERE empresa_id = :empresa")
                        .param("empresa", e)
                        .update();
            }
        }
    }

    /**
     * Mueve el inicio de una regla a una fecha fija, escrita como {@code LocalDate}
     * por JDBC, y las consultas reciben el mismo {@link #HOY}: así la prueba no
     * depende de la medianoche ni de que aplicación y base estén en zonas
     * horarias distintas.
     */
    private void empiezaEn(Integer reglaId, LocalDate desde) {
        jdbc.sql("UPDATE reglas_iva SET vigente_desde = :desde WHERE regla_iva_id = :id")
                .param("desde", desde)
                .param("id", reglaId)
                .update();
    }

    private Integer regla(Integer e, Integer cat, Integer prod, LocalDate desde, LocalDate hasta) {
        return repositorio.insertarRegla(e, cat, prod, tipoIva19, desde, hasta, null);
    }

    // ------------------------------------------------------ la configuración

    @Test
    void sinFilaNoHayConfiguracion() {
        assertTrue(repositorio.buscarConfiguracion(empresa).isEmpty());
    }

    @Test
    void guardarCreaLaFilaYGuardarOtraVezLaActualizaSinDuplicarla() {
        repositorio.guardarConfiguracion(empresa, true, true, true,
                new BigDecimal("0.009660"), true, 10);

        assertEquals(new ConfiguracionTributaria(true, true, true,
                        new BigDecimal("0.009660"), true, 10, true),
                repositorio.buscarConfiguracion(empresa).orElseThrow());

        // Segunda vez: otros valores, el ICA vuelve a nulo.
        repositorio.guardarConfiguracion(empresa, false, false, false, null, false, 30);

        assertEquals(new ConfiguracionTributaria(false, false, false, null, false, 30, true),
                repositorio.buscarConfiguracion(empresa).orElseThrow());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM empresa_config WHERE empresa_id = :e")
                .param("e", empresa).query(Integer.class).single());
    }

    @Test
    void guardarLaConfiguracionDeUnaEmpresaNoTocaLaDeOtra() {
        repositorio.guardarConfiguracion(otra, true, false, false, null, false, 7);

        repositorio.guardarConfiguracion(empresa, false, true, false, null, true, 15);

        assertEquals(7, repositorio.buscarConfiguracion(otra).orElseThrow()
                .cotizacionVigenciaDias());
        assertTrue(repositorio.buscarConfiguracion(otra).orElseThrow().responsableIva());
    }

    // ---------------------------------------------------------- el solape

    @Test
    void dosReglasGeneralesAbiertasSeSolapanAunqueLosAlcancesSeanNulos() {
        regla(empresa, null, null, ENERO, null);

        // Los dos alcances llegan como nulos al motor: el caso más común, y
        // el que una clave única no atraparía.
        assertTrue(repositorio.haySolape(empresa, null, null, LocalDate.of(2030, 1, 1), null));
        assertTrue(repositorio.haySolape(empresa, null, null, ENERO.minusYears(1),
                ENERO.plusDays(1)));
    }

    @Test
    void unaGeneralCerradaAntesDeLaAbiertaNoChoca() {
        regla(empresa, null, null, ENERO, null);

        assertFalse(repositorio.haySolape(empresa, null, null, ENERO.minusYears(1),
                ENERO.minusDays(1)));
    }

    @Test
    void unaReglaCerradaDejaLugarALaSiguiente() {
        Integer id = regla(empresa, null, null, ENERO, null);
        repositorio.cerrarRegla(empresa, id, LocalDate.of(2026, 6, 30));

        assertTrue(repositorio.haySolape(empresa, null, null, LocalDate.of(2026, 6, 30), null));
        assertFalse(repositorio.haySolape(empresa, null, null, LocalDate.of(2026, 7, 1), null));
    }

    @Test
    void cadaAlcanceEsPropio() {
        regla(empresa, categoria, null, ENERO, null);

        assertTrue(repositorio.haySolape(empresa, categoria, null, ENERO, null));
        // La general no choca con la de una categoría, ni al revés.
        assertFalse(repositorio.haySolape(empresa, null, null, ENERO, null));
        assertFalse(repositorio.haySolape(empresa, categoria + 1000, null, ENERO, null));
    }

    @Test
    void laReglaDeOtraEmpresaNoChocaConLaPropia() {
        regla(otra, null, null, ENERO, null);

        assertFalse(repositorio.haySolape(empresa, null, null, ENERO, null));
        assertTrue(repositorio.haySolape(otra, null, null, ENERO, null));
    }

    // ------------------------------------------------ cerrar y retirar

    @Test
    void cerrarPoneLaFechaYConservaLaFila() {
        Integer id = regla(empresa, categoria, null, ENERO, null);

        assertEquals(1, repositorio.cerrarRegla(empresa, id, LocalDate.of(2026, 12, 31)));

        var r = repositorio.buscarRegla(empresa, id, HOY).orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 31), r.vigenteHasta());
        assertEquals("Prueba", r.categoria());
        assertEquals("IVA_19", r.codigoImpuesto());
        assertEquals(1, r.especificidad());
    }

    @Test
    void retirarBorraLaFilaDeLaEmpresaYNoLaDeOtra() {
        // Solo se borra lo que todavía no empezó, según el «hoy» que se pasa y según el
        // día del motor (la segunda guarda): una fecha fija muy lejana cumple las dos.
        Integer propia = regla(empresa, null, null, ENERO, null);
        empiezaEn(propia, LEJANA);
        Integer ajena = regla(otra, null, null, ENERO, null);
        empiezaEn(ajena, LEJANA);

        // Con el identificador de la otra empresa no borra nada.
        assertEquals(0, repositorio.borrarRegla(empresa, ajena, HOY));
        assertTrue(repositorio.buscarRegla(otra, ajena, HOY).isPresent());

        assertEquals(1, repositorio.borrarRegla(empresa, propia, HOY));
        assertTrue(repositorio.buscarRegla(empresa, propia, HOY).isEmpty());
        assertTrue(repositorio.buscarRegla(otra, ajena, HOY).isPresent());
    }

    /**
     * Si entre la lectura y el borrado la regla pasa a regir en el «hoy» de la
     * sentencia, el DELETE no puede borrarla: una regla que rige explica ventas.
     */
    @Test
    void retirarNoBorraUnaReglaQueYaRigeSegunElHoyDeLaSentencia() {
        Integer queRigeHoy = regla(empresa, null, null, ENERO, null);
        empiezaEn(queRigeHoy, HOY);
        Integer queRigeDesdeAyer = regla(empresa, categoria, null, ENERO, null);
        empiezaEn(queRigeDesdeAyer, HOY.minusDays(1));

        assertEquals(0, repositorio.borrarRegla(empresa, queRigeHoy, HOY));
        assertEquals(0, repositorio.borrarRegla(empresa, queRigeDesdeAyer, HOY));

        assertTrue(repositorio.buscarRegla(empresa, queRigeHoy, HOY).isPresent());
        assertTrue(repositorio.buscarRegla(empresa, queRigeDesdeAyer, HOY).isPresent());
    }

    /**
     * La segunda guarda: aunque la aplicación pase un «hoy» anterior al del motor, el
     * DELETE no borra una regla que el motor ya tiene vigente, que es la que las
     * vistas aplican (ADR-0030). La fecha de la regla es fija y lejana al día de hoy,
     * de modo que no depende de la hora a la que corra la prueba.
     */
    @Test
    void retirarNoBorraUnaReglaQueElMotorYaTieneVigenteAunqueLaAplicacionDigaOtroDia() {
        Integer regla = regla(empresa, null, null, ENERO, null);
        empiezaEn(regla, LocalDate.of(2025, 1, 1));

        assertEquals(0, repositorio.borrarRegla(empresa, regla, LocalDate.of(2020, 1, 1)));

        assertTrue(repositorio.buscarRegla(empresa, regla, HOY).isPresent());
    }

    @Test
    void cerrarLaReglaDeOtraEmpresaNoCambiaNada() {
        Integer ajena = regla(otra, null, null, ENERO, null);

        assertEquals(0, repositorio.cerrarRegla(empresa, ajena, LocalDate.of(2026, 6, 30)));

        assertTrue(repositorio.buscarRegla(otra, ajena, HOY).orElseThrow().vigenteHasta() == null);
        assertTrue(repositorio.buscarRegla(empresa, ajena, HOY).isEmpty());
        assertEquals(1, repositorio.listarReglas(otra, HOY).size());
        assertTrue(repositorio.listarReglas(empresa, HOY).isEmpty());
    }

    // -------------------------------------------------------- el bloqueo

    @Test
    void bloquearLaEmpresaSeEjecutaSobreUnaFilaQueExiste() {
        // FOR UPDATE contra una empresa inexistente no lanza: lo que se
        // comprueba aquí es que la sentencia es válida y no falla.
        repositorio.bloquearEmpresa(empresa);
        repositorio.bloquearEmpresa(-1);
    }
}
