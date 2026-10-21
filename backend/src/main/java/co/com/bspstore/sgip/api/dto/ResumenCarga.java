package co.com.bspstore.sgip.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que la pantalla necesita saber de una carga de lista de precios.
 *
 * `leidos`, `aceptados` y `rechazados` vienen de la tabla y no se
 * recalculan al leer: el RF-27 pide que la carga «registre aplicados y
 * rechazados», de modo que esas cifras son el registro, no un resumen
 * que podría dar distinto si el catálogo cambió después.
 *
 * `muestra` son las primeras filas para la vista previa que el RF-24
 * exige antes de aplicar. No es la carga entera: una lista de veinte mil
 * referencias no cabe en una respuesta ni se revisa a ojo.
 */
public record ResumenCarga(
        Long cargaId,
        Integer proveedorId,
        String proveedor,
        String nombreArchivo,
        String estado,
        LocalDateTime fecha,
        LocalDateTime fechaAplicacion,
        int leidos,
        int aceptados,
        int rechazados,
        List<FilaDeCarga> muestra
) {}
