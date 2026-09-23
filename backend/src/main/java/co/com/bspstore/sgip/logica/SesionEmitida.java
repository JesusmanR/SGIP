package co.com.bspstore.sgip.logica;

import co.com.bspstore.sgip.modelo.Usuario;

import java.time.LocalDateTime;

public record SesionEmitida(Usuario usuario, String token, LocalDateTime expira) {}