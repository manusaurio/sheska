package ar.pelotude.ohhsugoi

import ar.pelotude.ohhsugoi.db.Demographic
import ar.pelotude.ohhsugoi.db.MangaWithTags
import ar.pelotude.ohhsugoi.koggable.Kog
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.interaction.respondPublic
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.Attachment
import dev.kord.rest.builder.interaction.*
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.async
import java.net.URL
import java.util.*

class MangaKog(
    val db: ar.pelotude.ohhsugoi.db.MangaDatabase,
    defaultServer: Snowflake = Snowflake(System.getenv("KORD_WEEB_SERVER")!!)
) : Kog(defaultServer) {
    private fun URL.seemsSafe() =
        protocol == "https" && host in listOf("media.discordapp.net", "cdn.discordapp.com")

    private fun Attachment?.isValidImage(): Boolean =
        this == null || isImage && size < 8000000 && URL(url).seemsSafe()

    override suspend fun setup() {
        inputCommand() {
            name = "buscar"
            description = "Busca un manga por título."

            command {
                string("text", "Texto a buscar en los títulos") { required = true }
            }

            handler {
                interaction.respondPublic {
                    val searchTerms = interaction.command.strings["text"]!!
                    val mangaList = db.searchManga(searchTerms)

                    mangaList.firstOrNull()?.let { manga: MangaWithTags ->
                        embed {
                            title = manga.title

                            manga.imgURLSource?. let { imgURL ->
                                thumbnail {
                                    url = imgURL.toString()
                                }
                            }

                            field {
                                name = "Categorías"

                                val tags = manga.tags.takeIf(Set<*>::isNotEmpty)?.joinToString(
                                    separator=", ",
                                    prefix=" ",
                                    postfix = ".".replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                ) ?: ""
                                value = "${manga.demographic}.$tags"
                            }

                            field {
                                name = "Tomos"
                                value = "${manga.volumes}"
                                inline = true
                            }
                            field {
                                name = "Páginas por tomo"
                                value = manga.pagesPerVolume?.let { "~$it" } ?: ""
                                inline = true
                            }
                            field {
                                name = "Páginas por capítulo"
                                value = "~${manga.pagesPerChapter}"
                                inline = true
                            }

                            description =
                                manga.description +
                                        "\n[Leer](${manga.link})"


                            footer {
                                text = if (manga.read) "Leído por el club." else "No leído por el club."
                            }
                        }
                    } ?: embed {
                        title = "No encontrado"
                        description = "No se encontró nada con los términos $searchTerms."
                    }
                }
            }
        }

        inputCommand {
            name = "sugerir"
            description = "Agrega un manga con los datos proporcionados."

            command {
                string("manga_title", "El título del manga") { required = true }
                // TODO: Store description `minLength` and `maxLength` in db, remove hardcoded values
                //  label: enhancement
                string("description", "El título del manga") { required = true; minLength = 20; maxLength = 500 }
                string("link", "Link para comprar o leer el manga.") { required = true }
                string("demographic", "Demografía del manga.") {
                    required = true
                    Demographic.values().forEach { n -> choice(n.alias, n.alias) }
                }

                string("tags", "Géneros del manga. Dividir por coma: \"tag uno, tag dos\".") {
                    required = true
                }

                int("chapters", "Cantidad de capítulos.") { required = true }
                int("ppc", "Cantidad de páginas por capítulo.")
                int("volumes", "Cantidad de tomos.")
                int("ppv", "Cantidad de páginas por tomo.")
                attachment("image", "Portada del manga.")
            }

            handler {
                val deferredResp = kord.async {
                    interaction.deferPublicResponse()
                }

                val c = interaction.command

                val title = c.strings["manga_title"]!!
                val description = c.strings["description"]!!
                val link = c.strings["link"]!!
                // TODO: Demographic check on manga submission crashes bot sometimes
                //  This is caused by the fact it excepcts to find the value of an enum as
                //  a string, but what's being stored are the alias, so they dont always match
                //  (`"OTHER" != "otros.uppercase()`)
                //  labels: bug
                val demographic = enumValueOf<Demographic>(c.strings["demographic"]!!.uppercase())
                val tags = c.strings["tags"]!!
                    .split(',')
                    .filter(String::isNotBlank)
                    .map(String::trim)
                    .toSet()

                val chapters = c.integers["chapters"]!!
                val volumes = c.integers["volumes"]
                val ppv = c.integers["ppv"]

                val ppc = c.integers["ppc"] ?: if (ppv != null && volumes != null)
                    ppv * volumes / chapters
                else -1

                val image: Attachment? = c.attachments["image"]
                val validImage: Boolean = image.isValidImage()

                val isValid = c.integers.values.all { it > 0 }
                        && c.strings.values.all(String::isNotBlank)
                        && c.strings["link"]!!.isValidURL()
                        && tags.isNotEmpty()
                        && ppc > 0
                        && validImage

                // TODO: set a cooldown for the user,
                //  check if similar titles exist,
                //  then add anyway but inform the user
                //  labels: enhancement
                val response = deferredResp.await()

                response.respond {
                    if (isValid) {
                        val operation = db.addManga(
                            title = title,
                            description = description,
                            imgURLSource = image?.let { URL(it.url) },
                            link = link,
                            demographic = demographic,
                            volumes = volumes,
                            pagesPerVolume = ppv,
                            chapters = chapters,
                            pagesPerChapter = ppc,
                            tags = tags,
                            read = false
                        )

                        content = if (operation != null) {
                            "Agregado exitosamente."
                        } else {
                            "Error al agregar."
                        }
                    } else {
                        // TODO: Point out the reason(s) the arguments validation didn't pass after trying to submit an entry.
                        content = "Error en los argumentos."
                    }
                }
            }
        }
    }
}