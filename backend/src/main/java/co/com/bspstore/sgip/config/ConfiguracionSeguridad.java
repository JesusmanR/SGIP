package co.com.bspstore.sgip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration
@EnableMethodSecurity
public class ConfiguracionSeguridad {

    /**
     * El reloj se inyecta en lugar de llamar a LocalDateTime.now() dentro
     * de la lógica. Es lo que permite comprobar la expiración de una
     * sesión en una prueba sin esperar media hora.
     */
    @Bean
    Clock reloj() {
        return Clock.systemDefaultZone();
    }

    /**
     * Un directorio de usuarios que no conoce a nadie.
     *
     * Parece inútil y no lo es: si no existe ningún UserDetailsService,
     * Spring Boot da por hecho que la aplicación todavía no configuró su
     * seguridad y crea un usuario en memoria con contraseña aleatoria,
     * que anuncia en el log en cada arranque. Aquí la autenticación la
     * resuelve FiltroSesion contra la tabla sesiones, de modo que ese
     * usuario sobra. Declararlo vacío es lo que hace que Boot se aparte.
     *
     * Hoy nadie podría usarlo porque httpBasic y formLogin están
     * deshabilitados. El problema es el día que alguien habilite
     * httpBasic para depurar y aparezca una credencial válida que no
     * está en la base de datos.
     */
    @Bean
    UserDetailsService directorioVacio() {
        return correo -> {
            throw new UsernameNotFoundException(correo);
        };
    }

    @Bean
    SecurityFilterChain cadena(HttpSecurity http, FiltroSesion filtroSesion,
                               FiltroEmpresaActiva filtroEmpresaActiva) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(filtroSesion, UsernamePasswordAuthenticationFilter.class)
                // Después del de sesión y no antes: necesita saber quién
                // hace la petición, y eso lo pone aquel en el contexto.
                // El modo de consulta de la RN-15 se decide aquí, delante
                // de todos los controladores, porque el requisito dice
                // «toda operación de escritura» y una comprobación
                // repartida por los servicios se cumple solo en los que
                // alguien se acordó de tocar.
                .addFilterAfter(filtroEmpresaActiva, FiltroSesion.class)
                // Sin esto, una petición sin credenciales recibe 403. Para un
                // cliente la diferencia importa: 401 significa «identifícate»
                // y 403 significa «ya sé quién eres y aun así no puedes».
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .build();
    }
}
