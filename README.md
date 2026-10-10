<p align="center">
  <img src="banner.png" alt="AutoAnvil by Welfarinas">
</p>

<p align="center">
  <a href="https://modrinth.com/mod/autoanvil-welfa1r"><img src="https://img.shields.io/badge/Modrinth-Descargar-1bd96a?logo=modrinth&logoColor=white&style=for-the-badge" alt="Modrinth"></a>
  <a href="https://github.com/welfa1r/AutoAnvilClient/releases"><img src="https://img.shields.io/badge/GitHub-Releases-181717?logo=github&logoColor=white&style=for-the-badge" alt="Releases"></a>
  <a href="https://github.com/welfa1r/AutoAnvilClient/issues"><img src="https://img.shields.io/badge/Reportar-Bug-d73a4a?logo=github&logoColor=white&style=for-the-badge" alt="Issues"></a>
  <a href="https://modrinth.com/mod/fabric-api"><img src="https://img.shields.io/badge/Requiere-Fabric%20API-dbb69b?style=for-the-badge" alt="Fabric API"></a>
  <a href="https://paypal.me/welfarinas"><img src="https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white&style=for-the-badge" alt="PayPal"></a>
</p>

Mod de cliente para **Fabric** (Minecraft **1.21.11**) que automatiza el encantado de equipo de netherite en el yunque. Eliges qué encantamientos quieres en cada pieza, abres un yunque y el mod coloca los libros y recoge los resultados por ti.

## Qué hace

- Configura **casco, pechera, pantalones, botas y espada** de netherite, cada una con sus propios encantamientos y niveles.
- Detecta en tu inventario las piezas, los **libros de un solo encantamiento** y la XP, y te muestra un **contador** de lo que tienes frente a lo que necesitas.
- Antes de empezar solo comprueba que haya **piezas** y **libros**. Si hay libros para parte de las unidades, encanta esas y avisa de las que faltan.
- La **XP se comprueba paso a paso**: cada uso del yunque se hace en cuanto puedes pagarlo. El total de una pieza es solo informativo.
- Solo funciona con el **yunque abierto**. En cualquier otra pantalla no actúa.
- Aplica los libros pieza a pieza, esperando la respuesta del servidor entre pasos.

## Opciones

- **Cantidad:** encanta varias piezas del mismo tipo de una vez (opcional).
- **Combinar libros:** con *Sí* busca el orden más barato en XP, juntando libros entre sí y sin pasar de 39 niveles por paso. Con *No* aplica un libro tras otro en el orden de tu lista.
- **Retardo entre clics:** escribe la cantidad (admite decimales, como `1.5`) y elige la unidad con el botón de al lado: ms, s, min o h. Va de 0 a 24 h. Encima se ve el equivalente en ticks y segundos (por ejemplo `≈ 3 ticks · 0,15 s`). El cliente hace como mucho un clic por tick (50 ms), así que cualquier valor por debajo de 50 ms, incluido 0, equivale al mínimo: un clic por tick. Entre pasos se sigue esperando la respuesta del servidor. En el JSON se guarda como `delayAmount` y `delayUnit`; un `clickDelayTicks` antiguo se pasa solo a ms.
- **Esperar XP:** con *Sí*, si no tienes niveles para el siguiente paso, espera a tenerlos y sigue. Con *No* hace los pasos que pueda y se detiene en el primero que no puedas pagar.
- **Coste por paso:** el menú muestra los niveles que costará cada paso y el total antes de empezar.

## Uso

1. Pulsa la tecla del mod (configurable en *Opciones > Controles*, por defecto `K`) para abrir el menú.
2. Elige una pestaña por pieza y marca los encantamientos y niveles que quieres.
3. Ten en el inventario las piezas de netherite y un libro por cada encantamiento.
4. Abre un yunque y pulsa el botón **Auto-encantar**.

El botón se usa en lugar de una tecla para que no se escriba nada en el campo de nombre del yunque.

## Instalación

1. Instala [Fabric Loader](https://fabricmc.net/use/) para Minecraft 1.21.11.
2. Descarga [Fabric API](https://modrinth.com/mod/fabric-api) y ponla en tu carpeta `mods`.
3. Descarga el `.jar` de AutoAnvil desde la sección **Releases** y ponlo en la misma carpeta.

## Compilar

Necesitas **JDK 21**.

```bash
./gradlew build
```

El `.jar` queda en `build/libs/`. Para probarlo directamente en el juego:

```bash
./gradlew runClient
```

En Windows usa `gradlew.bat` en lugar de `./gradlew`.

## Configuración

Los ajustes se guardan en `config/autoanvil.json` dentro de la carpeta de tu instancia de Minecraft. Se regenera con valores por defecto si falta o está dañado.

## Contribuir

Las mejoras son bienvenidas: abre un *issue* o un *pull request*.

## Licencia

MIT. Consulta el archivo `LICENSE`.
