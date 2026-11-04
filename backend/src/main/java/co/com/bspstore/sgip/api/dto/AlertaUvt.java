package co.com.bspstore.sgip.api.dto;

/**
 * El aviso de que falta la UVT de un año (arreglo 10).
 *
 * Es un aviso para quien opera la plataforma, no un bloqueo para nadie:
 * ninguna tienda deja de vender porque falte la cifra. La UVT solo cambia
 * el cálculo de la empresa responsable de IVA que activó la exclusión por
 * umbral, y a esa, sin la cifra, se le cobra la tarifa.
 *
 * @param activa         si hay algo de qué avisar. Inactiva, los otros dos
 *                       campos no dicen nada.
 * @param anio           el año cuya UVT falta.
 * @param diasRestantes  días que faltan para que ese año empiece; cero si
 *                       ya empezó, nunca negativo.
 */
public record AlertaUvt(boolean activa, int anio, long diasRestantes) {}
