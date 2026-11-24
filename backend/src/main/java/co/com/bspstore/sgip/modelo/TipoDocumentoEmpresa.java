package co.com.bspstore.sgip.modelo;

/**
 * El documento con que se identifica una empresa: el NIT de una sociedad o la cédula de una persona
 * natural. Los nombres son los del ENUM de {@code empresas.tipo_documento} (V48).
 *
 * No es {@link TipoDocumento}, el de los clientes: aquel admite cédula de extranjería, pasaporte y el
 * genérico de la DIAN, y una empresa solo puede ser una de estas dos cosas. Del número no se guarda ni se
 * pide dígito de verificación, sea cual sea el tipo (ver {@code logica.DocumentoEmpresa}).
 */
public enum TipoDocumentoEmpresa {
    NIT,
    CC
}
