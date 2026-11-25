package com.carlos.minube.net

/**
 * Rutas de FileBrowser Quantum 2.0.2, copiadas de `realApi` en index-quantum-v5.html (no inventar otras).
 *
 *  - Crear carpeta: POST /api/resources?source=SRC&path=qp(ruta + '/')&isDir=true   (409 = ya existe → vale)
 *  - Subir:         POST /api/resources?source=SRC&path=qp(ruta)&override=false, cuerpo = el archivo,
 *                   cabecera Authorization: Bearer TOKEN                              (409 = ya existe ese nombre)
 *  - Renovar:       POST /api/auth/renew con Authorization: Bearer TOKEN → el token nuevo como texto (a veces entre comillas)
 *
 * En la web, SRC = encodeURIComponent(CONFIG.source) y qp = encodeURIComponent: las barras de la ruta van como %2F.
 */
object Quantum {
    private const val HEX = "0123456789ABCDEF"
    private const val SIN_CODIFICAR = "-_.!~*'()"

    /** Equivalente exacto de encodeURIComponent de JavaScript (UTF-8, '/' → %2F, espacio → %20). */
    fun enc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val ascii = c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || SIN_CODIFICAR.indexOf(ch) >= 0)
            if (ascii) sb.append(ch) else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
        return sb.toString()
    }

    /** realApi.mkdir / _mkdirOk: la ruta de la carpeta termina en '/'. */
    fun urlCrearCarpeta(server: String, source: String, carpeta: String): String {
        val ruta = if (carpeta.endsWith("/")) carpeta else "$carpeta/"
        return "$server/api/resources?source=${enc(source)}&path=${enc(ruta)}&isDir=true"
    }

    /** realApi.upload: path = join(dir, nombre), override=false. */
    fun urlSubir(server: String, source: String, ruta: String): String =
        "$server/api/resources?source=${enc(source)}&path=${enc(ruta)}&override=false"

    fun urlRenovar(server: String): String = "$server/api/auth/renew"

    /** Igual que la web: (await r.text()).trim().replace(/^"|"$/g, '') */
    fun limpiarToken(t: String): String = t.trim().removePrefix("\"").removeSuffix("\"")
}
