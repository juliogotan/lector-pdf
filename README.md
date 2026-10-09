# Lector PDF (estilo Kindle)

## Compilar el APK (opción A: Android Studio, Windows)
1. Instala Android Studio.
2. File > Open > elige esta carpeta (LectorPDF). Espera a que termine el "Gradle sync".
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. El APK queda en app/build/outputs/apk/debug/app-debug.apk. Pásalo al móvil e instálalo.

## Compilar el APK (opción B: sin instalar nada, GitHub)
1. Sube esta carpeta a un repositorio de GitHub.
2. Pestaña Actions > "Build APK" > Run workflow.
3. Descarga el artefacto "LectorPDF-apk" cuando termine.

## Controles
- Toque en el borde derecho / izquierdo: página siguiente / anterior
- Deslizar horizontal (sin zoom): cambiar de página
- Toque en el centro: mostrar u ocultar barras (abrir, tema, barra de páginas)
- Doble toque: zoom a ancho de página / volver; pellizco: zoom libre
- Botones de volumen: pasar página
- "Tema": normal / sepia / noche
- Recuerda el último PDF y la página de cada PDF
