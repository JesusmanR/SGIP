SGIP - Diagramas UML
====================

png/     Imagenes listas para insertar en documentos o diapositivas.
fuente/  Codigo fuente PlantUML, editable en texto plano.

comun.iuml contiene la paleta y tipografia compartidas: editarlo
reestiliza todos los diagramas a la vez.

Correspondencia con las figuras del informe (version 4)
-------------------------------------------------------
Figura  1  01a_cu_seguridad_catalogo        Casos de uso: seguridad y catalogo
Figura  2  01b_cu_abastecimiento            Casos de uso: abastecimiento y precios
Figura  3  01c_cu_comercial                 Casos de uso: comercial, inventario y reportes
Figura  4  02a_clases_catalogo              Clases: modelo, catalogo y abastecimiento
Figura  5  02b_clases_comercial             Clases: modelo, comercial y facturacion
Figura  6  02c_clases_logica                Clases: logica, inventario y precios
Figura  7  02d_clases_logica_comercial      Clases: logica, servicios comerciales
Figura  8  02e_clases_seguridad             Clases: subsistema de seguridad
Figura  9  03a_sec_autenticacion            Secuencia: autenticacion
Figura 10  03b_sec_importacion_lectura      Secuencia: lectura y validacion de lista
Figura 11  03c_sec_importacion_aplicacion   Secuencia: aplicacion y recalculo
Figura 12  03d_sec_venta_factura            Secuencia: venta y factura electronica
Figura 13  04_actividad_importacion         Actividades: importacion de precios
Figura 14  05a_estados_producto             Estados: producto
Figura 15  05b_estados_cotizacion           Estados: cotizacion
Figura 16  05c_estados_factura              Estados: factura electronica
Figura 17  06_componentes                   Componentes
Figura 18  07_despliegue                    Despliegue
Figura 19  08a_er_seguridad                 Entidad-relacion: seguridad
Figura 20  08b_er_catalogo                  Entidad-relacion: catalogo y precios
Figura 21  08c_er_comercial                 Entidad-relacion: comercial
Figura 22  09_er_parametrizacion            Entidad-relacion: parametrizacion
Figura 37  08_entidad_relacion              Entidad-relacion completo (34 tablas)

Las figuras 23 a 36 son las pantallas, y estan en ../mockups/

Que cambio en la version 4
--------------------------
01a  Entra el super administrador de plataforma (BSP) como cuarto actor.
06   Entran el filtro de empresa, el servicio de sincronizacion y el
     almacen local de los clientes sin conexion.
07   El despliegue refleja el front web y los clientes React Native, no
     el modulo Swing.
08   El modelo completo pasa de 24 a 34 entidades.
08a  usuarios gana empresa_id; roles gana ambito.
08b  Entra producto_proveedor: el RF-21 pide varios proveedores por
     producto y faltaba la tabla intermedia.
08c  Entran medios_pago, cierres_caja y las columnas de venta sin
     existencia.
09   Las reglas de margen ganan forma_calculo.

El modelo completo (08) es ancho a proposito. Se acepto asi en lugar de
partirlo: su funcion es verse entero. La tipografia se subio a 20 para
que al imprimirlo en una pagina siga siendo legible.

Como editar y regenerar
-----------------------
    java -jar plantuml.jar -tpng nombre.puml

PlantUML: https://plantuml.com/es/download  (requiere Java 8+ y Graphviz)

Si el .jar no se descarga desde Maven Central, las publicaciones de
GitHub funcionan. Graphviz hace falta aparte: sin el, los diagramas de
clases y de entidad-relacion fallan sin mensaje claro.

    sudo apt-get install graphviz        # Linux
    choco install graphviz               # Windows

Para diagramas que no necesitan Graphviz se puede usar la directiva
!pragma layout smetana, que trae su propio motor de disposicion, con
resultados mas pobres en diagramas grandes.
