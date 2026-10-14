package co.com.bspstore.sgip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    @Bean
    SecurityFilterChain cadena(HttpSecurity http, FiltroSesion filtroSesion) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(filtroSesion, UsernamePasswordAuthenticationFilter.class)
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
