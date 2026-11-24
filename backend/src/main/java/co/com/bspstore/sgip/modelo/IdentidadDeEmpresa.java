package co.com.bspstore.sgip.modelo;

/**
 * Lo que identifica a una empresa y solo la plataforma cambia: el tipo y el número de su documento y su
 * razón social. {@code nit} es el texto tal como está guardado, que en las semillas puede llevar su dígito de verificación (NNNNNNNNN-D);
 * para compararlo con un número nuevo hay que normalizarlo antes.
 */
public record IdentidadDeEmpresa(TipoDocumentoEmpresa tipoDocumento, String nit, String razonSocial) {}
