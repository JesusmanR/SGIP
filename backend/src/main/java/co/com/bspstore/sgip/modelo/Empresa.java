package co.com.bspstore.sgip.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La empresa, vista desde dentro de ella.
 *
 * Solo mapea lo que el comercio consulta o cambia de sí mismo. El estado
 * de la suscripción y la fecha de alta los gobierna el módulo de
 * plataforma, y traerlos aquí invitaría a que una pantalla de empresa los
 * modificara.
 */
@Entity
@Table(name = "empresas")
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "empresa_id")
    private Integer id;

    @Column(name = "nit", nullable = false, length = 20)
    private String nit;

    @Column(name = "razon_social", nullable = false, length = 160)
    private String razonSocial;

    /** Ruta relativa a la raíz de almacenamiento, no los bytes (V13). */
    @Column(name = "logo_ruta", length = 255)
    private String logoRuta;

    protected Empresa() {
        // Requerido por JPA
    }

    public void cambiarLogo(String rutaRelativa) {
        this.logoRuta = rutaRelativa;
    }

    public Integer getId() { return id; }
    public String getNit() { return nit; }
    public String getRazonSocial() { return razonSocial; }
    public String getLogoRuta() { return logoRuta; }
}
