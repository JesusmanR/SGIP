package co.com.bspstore.sgip.api.dto;

import co.com.bspstore.sgip.modelo.Proveedor;

public record ProveedorResumen(
        Integer id,
        String razonSocial,
        String nit,
        String contacto,
        String correo,
        String telefono,
        int plazoDias
) {
    public static ProveedorResumen de(Proveedor p) {
        return new ProveedorResumen(p.getId(), p.getRazonSocial(), p.getNit(),
                p.getContacto(), p.getCorreo(), p.getTelefono(), p.getPlazoDias());
    }
}
