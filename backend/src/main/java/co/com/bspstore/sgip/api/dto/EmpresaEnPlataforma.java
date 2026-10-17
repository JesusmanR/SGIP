package co.com.bspstore.sgip.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * La empresa vista por la plataforma (RF-01 a RF-03).
 *
 * Es el reverso de {@code modelo.Empresa}, que mapea «la empresa vista
 * desde dentro de ella» y a propósito no trae el estado ni la
 * suscripción. Aquí sí vienen, porque son justo lo que el super
 * administrador gobierna.
 *
 * Lo que no trae, y no por olvido: nada operativo. Ni catálogo, ni
 * ventas, ni inventario, ni los correos de los usuarios del comercio. La
 * RN-17 dice que el super administrador «no accede a los datos
 * operativos de ningún negocio», y un conteo de usuarios es lo máximo
 * que hace falta para administrar un ciclo de vida: dice si la empresa
 * está habitada, sin decir por quién.
 */
public record EmpresaEnPlataforma(
        Integer empresaId,
        String nit,
        String razonSocial,
        String correoContacto,
        String telefono,
        String ciudad,
        String estado,
        LocalDateTime creadaEn,
        String plan,
        LocalDate inicioPrueba,
        LocalDate finPrueba,
        String estadoSuscripcion,
        long usuarios
) {}
