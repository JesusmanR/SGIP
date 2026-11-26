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
    PIN_BLOQUEADO,
    // La recuperación de la contraseña por correo (RF-65) deja su rastro en esta
    // misma bitácora. Ningún informe debe leerlos como ingresos: si uno llega a
    // usar `exitoso` como «último ingreso», tiene que filtrar RECUPERACION_*.
    RECUPERACION_SOLICITADA,
    RECUPERACION_CORREO_INEXISTENTE,
    RECUPERACION_CUENTA_INACTIVA,
    RECUPERACION_LIMITE,
    RECUPERACION_EXITOSA,
    RECUPERACION_CODIGO_INVALIDO
}
