package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudProveedor;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.ProveedorRepositorio;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ServicioProveedor {

    private final ProveedorRepositorio proveedores;

    public ServicioProveedor(ProveedorRepositorio proveedores) {
        this.proveedores = proveedores;
    }

    @Transactional(readOnly = true)
    public List<Proveedor> listar() {
        return proveedores.findByEmpresaIdAndActivoTrueOrderByRazonSocial(
                ContextoEmpresa.empresaActual());
    }

    @Transactional(readOnly = true)
    public Optional<Proveedor> buscar(Integer proveedorId) {
        return proveedores.findByEmpresaIdAndId(ContextoEmpresa.empresaActual(), proveedorId);
    }

    @Transactional
    public Proveedor crear(SolicitudProveedor s) {
        Integer empresa = ContextoEmpresa.empresaActual();
        String nit = Proveedor.normalizarNit(s.nit());

        if (proveedores.existsByEmpresaIdAndNit(empresa, nit)) {
            throw new NombreRepetidoException(
                    "Ya existe un proveedor con el NIT " + nit + " en esta empresa.");
        }

        Proveedor p = new Proveedor(empresa, s.razonSocial(), nit);
        p.contactar(s.contacto(), s.correo(), s.telefono());
        p.fijarPlazo(s.plazoDias() == null ? 0 : s.plazoDias());
        return proveedores.save(p);
    }
}
