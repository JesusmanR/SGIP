package co.com.bspstore.sgip.api.dto;

/**
 * Un administrador de la empresa que puede autorizar con su PIN: lo que el vendedor
 * ve para elegirlo. Solo el identificador y el nombre; ni el correo ni nada más.
 */
public record AdministradorParaAutorizar(Integer id, String nombre) {
}
