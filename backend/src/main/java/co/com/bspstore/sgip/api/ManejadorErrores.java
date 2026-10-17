package co.com.bspstore.sgip.api;

import co.com.bspstore.sgip.config.SinEmpresaException;
import co.com.bspstore.sgip.logica.CategoriaEnUsoException;
import co.com.bspstore.sgip.logica.CredencialesInvalidasException;
import co.com.bspstore.sgip.logica.FiltroInvalidoException;
import co.com.bspstore.sgip.logica.AlmacenArchivos;
import co.com.bspstore.sgip.logica.ArchivoInvalidoException;
import co.com.bspstore.sgip.logica.NombreRepetidoException;
import co.com.bspstore.sgip.logica.RegistroRepetidoException;
import co.com.bspstore.sgip.logica.ReferenciaAjenaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ProblemDetail credencialesInvalidas(CredencialesInvalidasException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        p.setTitle("Credenciales inválidas");
        p.setDetail("El correo o la contraseña no son correctos.");
        return p;
    }

    @ExceptionHandler(SinEmpresaException.class)
    public ProblemDetail sinEmpresa(SinEmpresaException e) {
        // 403 y no 401: sabemos quién es y aun así no puede.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Operación propia de una empresa");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(NombreRepetidoException.class)
    public ProblemDetail nombreRepetido(NombreRepetidoException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Nombre repetido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(RegistroRepetidoException.class)
    public ProblemDetail registroRepetido(RegistroRepetidoException e) {
        // 409 igual que el nombre repetido, pero con otro título: aquí la
        // unicidad es de toda la plataforma y no de dentro de una empresa,
        // y el detalle dice cuál de los dos datos del alta choca.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Dato ya registrado");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ReferenciaAjenaException.class)
    public ProblemDetail referenciaAjena(ReferenciaAjenaException e) {
        // 422 y no 404: la petición está bien formada y el recurso puede
        // existir; lo que no es válido es usarlo desde esta empresa.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        p.setTitle("Referencia de otra empresa");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(CategoriaEnUsoException.class)
    public ProblemDetail categoriaEnUso(CategoriaEnUsoException e) {
        // 409 y no 422: la referencia es propia y válida; lo que impide
        // atender la petición es el estado actual del catálogo.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Categoría en uso");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(FiltroInvalidoException.class)
    public ProblemDetail filtroInvalido(FiltroInvalidoException e) {
        // Llega por la dirección y no por el cuerpo, de modo que la
        // validación declarativa no lo alcanza.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Filtro no válido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(ArchivoInvalidoException.class)
    public ProblemDetail archivoInvalido(ArchivoInvalidoException e) {
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Archivo no admitido");
        p.setDetail(e.getMessage());
        return p;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail archivoDemasiadoGrande(MaxUploadSizeExceededException e) {
        // Este tope lo aplica el contenedor antes de que el controlador
        // vea nada, de modo que no puede comprobarlo AlmacenArchivos.
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        p.setTitle("Archivo demasiado grande");
        p.setDetail("La imagen supera el tamaño permitido. Usa una de hasta "
                + (AlmacenArchivos.BYTES_MAXIMOS / 1024 / 1024) + " MB.");
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail datosInvalidos(MethodArgumentNotValidException e) {
        String mensajes = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Datos inválidos");
        p.setDetail(mensajes);
        return p;
    }
}