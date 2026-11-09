package co.com.bspstore.sgip.logica;

import java.time.OffsetDateTime;

/**
 * Cómo está la prueba gratuita de una empresa en un instante (RF-73).
 *
 * {@code PRUEBA_VENCIDA} no es un estado de la base: el ENUM de
 * {@code empresas.estado} sigue diciendo PRUEBA hasta que la plataforma active o
 * desactive (ADR-0031). Es lo que el reloj deduce de esa PRUEBA y de su
 * {@code fin_prueba}, y por eso vive en un tipo de respuesta y no en
 * {@code EstadoEmpresa}.
 *
 * {@code venceEn} y {@code segundosRestantes} son nulos cuando no hay prueba que
 * contar: en ACTIVA y en DESACTIVADA.
 */
public record SituacionDePrueba(Estado estado, OffsetDateTime venceEn, Long segundosRestantes) {

    public enum Estado { PRUEBA, PRUEBA_VENCIDA, ACTIVA, DESACTIVADA }
}
