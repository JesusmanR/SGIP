# Cómo llevar esto al repositorio

El contenido de esta carpeta reemplaza la raíz del repositorio. Tres
cosas cambian respecto de lo que está publicado hoy:

1. Los documentos estaban desactualizados. Se reemplazan por la versión
   vigente y se mueven a `docs/informe/`.
2. `SGIP_Diagramas_UML.zip` y la carpeta `SGIP_Diagramas/` guardaban lo
   mismo dos veces. Se conserva solo la carpeta, ahora en
   `docs/diagramas/`, porque el control de versiones ya cumple la función
   del comprimido y además permite ver los cambios de cada `.puml`.
3. Se añade el esquema de base de datos, el registro de decisiones, la
   estructura de código y la integración continua.

## Pasos

```bash
cd ruta/a/tu/copia/de/SGIP
git checkout -b sprint-0-estructura

# Retirar lo que queda fuera de la nueva estructura
git rm -r --cached SGIP_Diagramas_UML.zip SGIP_Diagramas
rm -rf SGIP_Diagramas_UML.zip SGIP_Diagramas
rm -f SGIP_Informe_Inicio_Proyecto.docx SGIP_Anexo_E_Casos_de_Uso.docx

# Copiar el contenido de esta carpeta sobre la raíz del repositorio
cp -r /ruta/donde/descomprimiste/SGIP/. .
rm COMO_ACTUALIZAR_EL_REPO.md

git add -A
git commit -m "Sprint 0: estructura del repositorio, esquema de base de datos y decisiones de arquitectura"
git push -u origin sprint-0-estructura
```

Abre el pull request hacia `main` y revisa el resultado del flujo de
integración continua antes de fusionar: aplica las siete migraciones
sobre un MySQL 8 limpio y verifica que produzcan veinticuatro tablas y
seis vistas.

## Antes del primer push

Revisa que `.gitignore` esté en su sitio. Un `application.yml` con
credenciales de base de datos en el historial de un repositorio público
no se borra con un commit posterior: queda para siempre y hay que rotar
la contraseña.

## Dos ramas más

El flujo descrito en el README usa `main` para lo terminado y `develop`
para la integración del sprint. Conviene crear `develop` ahora:

```bash
git checkout main
git checkout -b develop
git push -u origin develop
```
