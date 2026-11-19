package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.api.dto.DevolucionRegistrada;
import co.com.bspstore.sgip.api.dto.SaldoDeCliente;
import co.com.bspstore.sgip.api.dto.SolicitudDevolucion;
import co.com.bspstore.sgip.logica.ServicioDevoluciones;
import co.com.bspstore.sgip.logica.ServicioSaldoAFavor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Devoluciones, anulaciones y saldo a favor de la caja (RF-68). Los usan el administrador y el vendedor;
 * el cliente y el super administrador reciben 403.
 *
 * <ul>
 *   <li>{@code POST /devoluciones}: devuelve unidades de una venta, o la anula entera si la lista de
 *       unidades va vacía. Siempre pide la autorización de un administrador (el administrador no necesita
 *       PIN). 201 con la devolución.</li>
 *   <li>{@code GET /clientes/{id}/saldo}: lo que el cliente tiene a favor y el medio de pago con que se
 *       gasta ({@code {saldo, medioId}}). 404 si el cliente es de otra empresa; el Consumidor final
 *       responde cero.</li>
 * </ul>
 *
 * Una empresa en consulta lee el saldo y no devuelve ({@code FiltroEmpresaActiva}).
 */
@RestController
@RequestMapping("/api/v1/caja")
@PreAuthorize("hasAnyRole('ADMINISTRADOR', 'VENDEDOR')")
public class DevolucionesControlador {

    private final ServicioDevoluciones devoluciones;
    private final ServicioSaldoAFavor saldos;

    public DevolucionesControlador(ServicioDevoluciones devoluciones, ServicioSaldoAFavor saldos) {
        this.devoluciones = devoluciones;
        this.saldos = saldos;
    }

    @PostMapping("/devoluciones")
    public ResponseEntity<DevolucionRegistrada> devolver(@Valid @RequestBody SolicitudDevolucion solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(devoluciones.registrar(solicitud));
    }

    @GetMapping("/clientes/{id}/saldo")
    public SaldoDeCliente saldo(@PathVariable Integer id) {
        return saldos.consultar(id);
    }
}
