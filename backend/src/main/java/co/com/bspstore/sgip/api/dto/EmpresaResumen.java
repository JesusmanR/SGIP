package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Empresa;
import co.com.bspstore.sgip.modelo.TipoDocumentoEmpresa;

/**
 * La empresa vista desde dentro de ella: su identidad (de solo lectura para el comercio), los datos de
 * contacto que el administrador edita y el logo.
 *
 * El cliente que edita los datos manda SOLO los cinco de contacto, nunca este resumen de vuelta: el PUT
 * rechaza cualquier otro campo con un 400.
 */
public record EmpresaResumen(Integer id, String nit, String razonSocial, String logoUrl,
                             TipoDocumentoEmpresa tipoDocumento, String nombreComercial, String direccion,
                             String ciudad, String telefono, String correoContacto) {

    public static EmpresaResumen de(Empresa e) {
        return new EmpresaResumen(e.getId(), e.getNit(), e.getRazonSocial(),
                e.getLogoRuta() == null ? null : "/api/v1/empresa/logo",
                e.getTipoDocumento(), e.getNombreComercial(), e.getDireccion(), e.getCiudad(),
                e.getTelefono(), e.getCorreoContacto());
    }
}
