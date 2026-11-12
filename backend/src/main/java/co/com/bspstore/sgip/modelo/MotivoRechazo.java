package co.com.bspstore.sgip.modelo;

public enum MotivoRechazo {
    USUARIO_INEXISTENTE,
    CLAVE_INCORRECTA,
    CUENTA_BLOQUEADA,
    CUENTA_INACTIVA,
    // La autorización con PIN (ADR-0032) deja su rastro en esta misma bitácora:
    // es la misma adivinanza que la clave, hecha desde una sesión abierta.
    PIN_INCORRECTO,
    PIN_OBJETIVO_INVALIDO,
    PIN_BLOQUEADO
}
