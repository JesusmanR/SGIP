package co.com.bspstore.sgip.api.dto;

/**
 * Lo que llega al pedir el código de recuperación (RF-65).
 *
 * Sin validaciones a propósito: un correo vacío, mal escrito o demasiado largo recibe la misma
 * respuesta que uno bueno, y rechazarlo con un 400 le diría a quien prueba qué formato vale.
 */
public record SolicitudRecuperacion(String correo) {}
