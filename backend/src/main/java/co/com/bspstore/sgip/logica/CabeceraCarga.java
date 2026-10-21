package co.com.bspstore.sgip.logica;

import java.time.LocalDateTime;

/**
 * La fila de `cargas_precios`, sin las filas de detalle.
 *
 * Existe aparte del {@code ResumenCarga} que ve la pantalla porque son
 * dos consultas distintas: la cabecera sale de una tabla y la muestra de
 * otra, y juntarlas en una sola sentencia obligaría a repetir la
 * cabecera en cada fila de detalle para después descartarla.
 */
public record CabeceraCarga(
        Long cargaId,
        Integer proveedorId,
        String proveedor,
        String nombreArchivo,
        String estado,
        LocalDateTime fecha,
        LocalDateTime fechaAplicacion,
        int leidos,
        int aceptados,
        int rechazados
) {}
