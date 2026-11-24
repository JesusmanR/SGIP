package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.api.dto.EmpresaResumen;
import co.com.bspstore.sgip.api.dto.SolicitudDatosEmpresa;
import co.com.bspstore.sgip.config.ContextoEmpresa;
import co.com.bspstore.sgip.datos.EmpresaRepositorio;
import co.com.bspstore.sgip.modelo.Empresa;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que el administrador cambia de su propia empresa: los datos de contacto que salen en el recibo.
 *
 * La empresa sale del contexto de seguridad y nunca del cuerpo, de modo que no hay forma de pedir la de
 * otro comercio. Lo que NO está aquí es la identidad (NIT o CC, número y razón social): la cambia solo la
 * plataforma, con motivo y bitácora, en {@link ServicioPlataforma#cambiarIdentidad}. Que esta clase no
 * pueda tocarla no depende de la buena voluntad de quien la llame: la entidad {@link Empresa} no tiene cómo
 * escribirla.
 */
@Service
public class ServicioEmpresa {

    private final EmpresaRepositorio empresas;

    public ServicioEmpresa(EmpresaRepositorio empresas) {
        this.empresas = empresas;
    }

    /**
     * Sustituye los cinco datos de contacto de la empresa de la sesión y devuelve cómo quedó.
     * {@code flush} para que, si la base rechazara algo, el error salga aquí y no en el cierre de la
     * transacción.
     */
    @Transactional
    public EmpresaResumen actualizarDatos(SolicitudDatosEmpresa solicitud) {
        Empresa empresa = empresas.findById(ContextoEmpresa.empresaActual())
                .orElseThrow(() -> new RecursoNoEncontradoException("La empresa no existe."));
        empresa.actualizarContacto(solicitud.nombreComercial(), solicitud.direccion(), solicitud.ciudad(),
                solicitud.telefono(), solicitud.correoContacto());
        empresas.flush();
        return EmpresaResumen.de(empresa);
    }
}
