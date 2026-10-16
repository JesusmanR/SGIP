package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Empresa;

public record EmpresaResumen(Integer id, String nit, String razonSocial, String logoUrl) {

    public static EmpresaResumen de(Empresa e) {
        return new EmpresaResumen(e.getId(), e.getNit(), e.getRazonSocial(),
                e.getLogoRuta() == null ? null : "/api/v1/empresa/logo");
    }
}
