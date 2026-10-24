package co.com.bspstore.sgip.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Todo lo que la pantalla P-02 necesita, en una respuesta.
 *
 * Cinco elementos y una sola petición. Podrían ser cinco endpoints, uno
 * por tarjeta, y a primera vista parecería más limpio; es peor por dos
 * motivos concretos. El panel es la primera pantalla tras el acceso del
 * administrador, de modo que cinco peticiones en paralelo son el primer
 * impacto que recibe el servidor en cada sesión. Y las cifras se leerían
 * en cinco instantes distintos: el conteo de agotadas podría venir de
 * antes de un movimiento y la tabla de críticas de después, y la
 * pantalla mostraría dos verdades que no concuerdan sin que nada falle.
 *
 * Nada aquí se filtra en la interfaz. Toda consulta va acotada por
 * `empresa_id` en la propia sentencia, que es lo que exige la RN-16.
 *
 * `ultimaCarga` es nulo cuando la empresa no ha registrado ninguna
 * lista de precios, que es uno de los tres estados que el mockup pide
 * diseñar. No es un error ni una lista vacía: es la ausencia de un
 * hecho, y la tarjeta invita a configurar un proveedor.
 */
public record Panel(
        Cifras cifras,
        List<ExistenciaCritica> criticas,
        CargaEnPanel ultimaCarga,
        List<CruceDeUmbral> cruces,
        List<DiaDeMovimiento> movimientos
) {

    /**
     * Las cuatro cifras de la cabecera.
     *
     * `capitalAlCosto` sale de `v_inventario_valorizado`, que desde la
     * V19 no resta lo que se debe reponer: una referencia en negativo
     * valoriza en cero y su deuda se declara aparte (ADR-0019). Por eso
     * `unidadesPendientes` viaja al lado, porque un capital que ignora
     * la deuda sin decirlo se lee como un inventario más sano de lo que
     * es.
     *
     * `altasDeLaSemana` es el subtítulo que el mockup pone bajo las
     * referencias activas. Son siete días corridos y no la semana
     * natural: «esta semana» en un panel que se mira cualquier día
     * significa «lo reciente», y la semana natural daría casi cero cada
     * lunes.
     */
    public record Cifras(
            int referenciasActivas,
            int altasDeLaSemana,
            int enExistenciaCritica,
            int agotadas,
            BigDecimal capitalAlCosto,
            BigDecimal capitalAPrecioVenta,
            int unidadesPendientes
    ) {}

    /**
     * Una fila de la tabla corta de existencias críticas.
     *
     * Lleva `productoId` porque el mockup pide «acceso directo a cada
     * ficha», y `contactoProveedor` porque la decisión que esta tabla
     * provoca es llamar a quien reabastece. Sin el teléfono, la tabla
     * informa y obliga a navegar a otra pantalla para actuar.
     */
    public record ExistenciaCritica(
            Integer productoId,
            String codigo,
            String nombre,
            String presentacion,
            String categoria,
            String proveedor,
            String contactoProveedor,
            int existencia,
            int existenciaMinima,
            String estadoStock
    ) {}

    /**
     * La tarjeta de la última carga de precios.
     *
     * Es `ResumenCarga` sin la muestra ni los cruces: el panel enseña el
     * resultado, no la vista previa, y los cruces van en su propio
     * elemento porque el mockup los presenta como un aviso aparte y no
     * como un dato de la carga.
     */
    public record CargaEnPanel(
            Long cargaId,
            String proveedor,
            String nombreArchivo,
            String estado,
            LocalDateTime fecha,
            LocalDateTime fechaAplicacion,
            int leidos,
            int aceptados,
            int rechazados
    ) {}

    /**
     * Un día del gráfico de movimiento, con sus dos barras.
     *
     * Vienen solo los días con movimiento. Rellenar los treinta es
     * trabajo de la pantalla, que es quien sabe cuántos puntos le caben
     * y cómo dibuja un día vacío; el backend no debe inventar treinta
     * filas de ceros para que el consumidor no tenga que pensar.
     */
    public record DiaDeMovimiento(
            LocalDate fecha,
            int ingresos,
            int egresos
    ) {}
}
