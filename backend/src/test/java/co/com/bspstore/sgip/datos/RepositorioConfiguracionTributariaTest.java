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
     * Mueve el inicio de una regla respecto del día de la base, calculado en SQL
     * y no con el reloj de la prueba: así no depende de la medianoche ni de que
     * aplicación y base estén en zonas horarias distintas.
     */
    private void empiezaEn(Integer reglaId, String expresionSql) {
        jdbc.sql("UPDATE reglas_iva SET vigente_desde = " + expresionSql
                        + " WHERE regla_iva_id = :id")
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

        var r = repositorio.buscarRegla(empresa, id).orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 31), r.vigenteHasta());
        assertEquals("Prueba", r.categoria());
        assertEquals("IVA_19", r.codigoImpuesto());
        assertEquals(1, r.especificidad());
    }

    @Test
    void retirarBorraLaFilaDeLaEmpresaYNoLaDeOtra() {
        // Solo se borra lo que todavía no empezó: mañana, según la base.
        Integer propia = regla(empresa, null, null, ENERO, null);
        empiezaEn(propia, "CURDATE() + INTERVAL 1 DAY");
        Integer ajena = regla(otra, null, null, ENERO, null);
        empiezaEn(ajena, "CURDATE() + INTERVAL 1 DAY");

        // Con el identificador de la otra empresa no borra nada.
        assertEquals(0, repositorio.borrarRegla(empresa, ajena));
        assertTrue(repositorio.buscarRegla(otra, ajena).isPresent());

        assertEquals(1, repositorio.borrarRegla(empresa, propia));
        assertTrue(repositorio.buscarRegla(empresa, propia).isEmpty());
        assertTrue(repositorio.buscarRegla(otra, ajena).isPresent());
    }

    /**
     * El servicio decide con su reloj que una regla «aún no empieza»; si la base
     * ya está en el día en que rige, el DELETE no puede borrarla: una regla que
     * rige explica ventas.
     */
    @Test
    void retirarNoBorraUnaReglaQueLaBaseYaTieneVigente() {
        Integer queRigeHoy = regla(empresa, null, null, ENERO, null);
        empiezaEn(queRigeHoy, "CURDATE()");
        Integer queRigeDesdeAyer = regla(empresa, categoria, null, ENERO, null);
        empiezaEn(queRigeDesdeAyer, "CURDATE() - INTERVAL 1 DAY");

        assertEquals(0, repositorio.borrarRegla(empresa, queRigeHoy));
        assertEquals(0, repositorio.borrarRegla(empresa, queRigeDesdeAyer));

        assertTrue(repositorio.buscarRegla(empresa, queRigeHoy).isPresent());
        assertTrue(repositorio.buscarRegla(empresa, queRigeDesdeAyer).isPresent());
    }

    @Test
    void cerrarLaReglaDeOtraEmpresaNoCambiaNada() {
        Integer ajena = regla(otra, null, null, ENERO, null);

        assertEquals(0, repositorio.cerrarRegla(empresa, ajena, LocalDate.of(2026, 6, 30)));

        assertTrue(repositorio.buscarRegla(otra, ajena).orElseThrow().vigenteHasta() == null);
        assertTrue(repositorio.buscarRegla(empresa, ajena).isEmpty());
        assertEquals(1, repositorio.listarReglas(otra).size());
        assertTrue(repositorio.listarReglas(empresa).isEmpty());
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
