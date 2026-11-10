package co.com.bspstore.sgip.modelo;

/**
 * El tipo de documento de un cliente (RF-66).
 *
 * Coincide con el ENUM de la columna {@code clientes.tipo_documento}. El
 * «Consumidor final» usa siempre {@link #CC}.
 */
public enum TipoDocumento {
    NIT,
    CC,
    CE,
    PASAPORTE
}
