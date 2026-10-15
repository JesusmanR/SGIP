package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.SolicitudProveedor;
import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.config.UsuarioAutenticado;
import co.com.bspstore.sgip.datos.ProveedorRepositorio;
import co.com.bspstore.sgip.modelo.Proveedor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicioProveedorTest {

    @Mock ProveedorRepositorio proveedores;
    @InjectMocks ServicioProveedor servicio;

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void comoEmpresa(Integer empresaId) {
        var u = new UsuarioAutenticado(7, "ana@tienda.co", "ADMINISTRADOR", empresaId);
        SecurityContext c = SecurityContextHolder.createEmptyContext();
        c.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, List.of()));
        SecurityContextHolder.setContext(c);
    }

    private SolicitudProveedor solicitud(String nit) {
        return new SolicitudProveedor("Distribuidora La Esperanza", nit,
                "Marta Gómez", "ventas@esperanza.co", "6076543210", 30);
    }

    @Test
    void listaSoloLosProveedoresDeSuEmpresa() {
        comoEmpresa(3);
        when(proveedores.findByEmpresaIdAndActivoTrueOrderByRazonSocial(3)).thenReturn(List.of());

        servicio.listar();

        verify(proveedores).findByEmpresaIdAndActivoTrueOrderByRazonSocial(3);
    }

    @Test
    void guardaConLaEmpresaDelContexto() {
        comoEmpresa(3);
        when(proveedores.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(solicitud("900.123.456-7"));

        ArgumentCaptor<Proveedor> captor = ArgumentCaptor.forClass(Proveedor.class);
        verify(proveedores).save(captor.capture());
        assertEquals(3, captor.getValue().getEmpresaId());
    }

    @Test
    void elNitSeGuardaSinPuntosNiGuiones() {
        // «900.123.456-7» y «9001234567» son el mismo proveedor. Sin
        // normalizar, la restricción única no lo vería y el comercio
        // acabaría con el mismo proveedor dos veces.
        comoEmpresa(3);
        when(proveedores.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(solicitud("  900.123.456-7  "));

        ArgumentCaptor<Proveedor> captor = ArgumentCaptor.forClass(Proveedor.class);
        verify(proveedores).save(captor.capture());
        assertEquals("9001234567", captor.getValue().getNit());
    }

    @Test
    void elNitRepetidoSeDetectaAunqueVengaEscritoDistinto() {
        comoEmpresa(3);
        when(proveedores.existsByEmpresaIdAndNit(3, "9001234567")).thenReturn(true);

        assertThrows(NombreRepetidoException.class,
                () -> servicio.crear(solicitud("900-123-456 7")));
        verify(proveedores, never()).save(any());
    }

    @Test
    void sinPlazoSeEntiendeDeContado() {
        comoEmpresa(3);
        when(proveedores.save(any())).thenAnswer(i -> i.getArgument(0));

        servicio.crear(new SolicitudProveedor("Panadería El Trigo", "9007654321",
                null, null, null, null));

        ArgumentCaptor<Proveedor> captor = ArgumentCaptor.forClass(Proveedor.class);
        verify(proveedores).save(captor.capture());
        assertEquals(0, captor.getValue().getPlazoDias());
    }

    @Test
    void elUsuarioDePlataformaNoTieneProveedores() {
        comoEmpresa(null);

        assertThrows(SinEmpresaException.class, () -> servicio.listar());
        assertThrows(SinEmpresaException.class, () -> servicio.crear(solicitud("9001234567")));
        verify(proveedores, never()).save(any());
    }
}
