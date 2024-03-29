package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.bot.converters.list
import ar.pelotude.ohhsugoi.bot.converters.optionalList
import ar.pelotude.ohhsugoi.db.MangaChanges
import ar.pelotude.ohhsugoi.db.MangaDatabase
import ar.pelotude.ohhsugoi.db.UpdateFlags
import ar.pelotude.ohhsugoi.db.demographics
import ar.pelotude.ohhsugoi.util.identity
import ar.pelotude.ohhsugoi.util.image.UnsupportedDownloadException
import ar.pelotude.ohhsugoi.util.isValidURL
import com.kotlindiscord.kord.extensions.checks.hasRole
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalStringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.utils.suggestStringCollection
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.Attachment
import dev.kord.core.entity.interaction.AutoCompleteInteraction
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.kordLogger
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.embed
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.io.IOException
import java.net.URL

class MangaExtension: Extension(), KordExKoinComponent {
    private val db: MangaDatabase by inject()
    private val config: MangaExtensionConfiguration by inject()

    override val name = "manga"

    private fun Attachment?.isValidImage(): Boolean =
            this == null || isImage && size < 8000000

    val titleAutoCompletion: (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit) = {
        val typedIn = focusedOption.value

        val results = db.searchMangaTitle(typedIn).map(Pair<*, String>::component2)

        suggestString {
            results.forEach { title -> choice(title, title) }
        }
    }

    val titleAutoCompletionIdNamePairs:
            (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit)?
            by inject(named("mangaIdAutoCompletion"))

    val tagAutoCompletion: (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit) = {
        val typedIn = focusedOption.value

        suggestStringCollection(db.searchTags(typedIn))
    }

    val multipleTagsAutoCompletion: (suspend AutoCompleteInteraction.(AutoCompleteInteractionCreateEvent) -> Unit) = {
        val typedIn: List<String> = focusedOption.value.split(',')

        val results: Collection<String> = typedIn.takeIf(List<*>::isNotEmpty)?.let {
            val previous: String? = it.dropLast(1).joinToString(",").takeIf(String::isNotBlank)
            val searchTerm: String = typedIn.last().trim()

            val candidates = db.searchTags(searchTerm)

            return@let previous?.let { candidates.map { c -> "$previous, $c" } } ?: candidates
        } ?: listOf()

        suggestStringCollection(results, suggestInputWithoutMatches=true)
    }

    inner class AddMangaArgs : Arguments() {
        val title by string {
            name = "título"
            description = "El título del manga"
            minLength = config.mangaTitleMinLength
            maxLength = config.mangaTitleMaxLength

            validate {
                failIf("El título está en blanco po") {
                    value.isBlank()
                }
            }
        }

        val description by string {
            name = "descripción"
            description ="Descripción del manga"
            minLength = config.mangaDescMinLength
            maxLength = config.mangaDescMaxLength

            validate {
                failIf("Ahí no hay descripción po") {
                    value.isBlank()
                }
            }
        }

        val link by string {
            name = "link"
            description ="Link para comprar o leer el manga"
            maxLength = config.mangaLinkMaxLength

            validate {
                failIfNot("El link no es válido") {
                    value.isValidURL()
                }
            }
        }

        val demographic by stringChoice {
            name = "demografía"
            description ="Demografía del manga"

            demographics.forEach {
                choice(it, it)
            }
        }

        val tags by list(identity()) {
            name = "tags"
            description ="Géneros del manga. Dividir por coma: \"tag uno, tag dos\""

            validate {
                failIf("No hay tags po sacowea") {
                    value.isEmpty()
                }
            }

            autoCompleteCallback = multipleTagsAutoCompletion
        }

        val chapters by optionalLong {
            name = "capítulos"
            description ="Cantidad de capítulos del manga"
            minValue = 1
        }

        val pagesPerChapter by optionalLong {
            name = "páginasporcapítulo"
            description ="Cantidad de páginas aproximada por capítulo"
            minValue = 1
        }

        val volumes by optionalLong {
            name = "tomos"
            description = "Cantidad de tomos del manga"
            minValue = 1
        }

        val pagesPerVolume by optionalLong {
            name = "páginasportomo"
            description = "Cantidad de páginas por tomo"
            minValue = 1
        }

        val image by optionalAttachment {
            name = "imagen"
            description = "Portada del manga"
            validate {
                failIfNot("El archivo excede el límite de peso") {
                    value.isValidImage()
                }
            }
        }
    }

    inner class SearchMangaArgs : Arguments() {
        val title by optionalString {
            name = "título"
            description = "El título del manga a buscar"

            autoCompleteCallback = titleAutoCompletion
        }

        val tagA by optionalString {
            name = "tag_a"
            description = "Tag por el cual filtrar"

            autoCompleteCallback = tagAutoCompletion
        }

        val tagB by optionalString {
            name = "tag_b"
            description = "Tag por el cual filtrar"

            autoCompleteCallback = tagAutoCompletion
        }

        val demographic by optionalStringChoice {
            name = "demografía"
            description = "Demografía por la cual filtrar"

            demographics.forEach {
                choice(it, it)
            }
        }
    }

    inner class GroupMangaEntriesArguments: Arguments() {
        val paramDescription = "Manga a mostrar"

        val mangaA by long {
            name = "manga_a"
            description = paramDescription
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val mangaB by optionalLong {
            name = "manga_b"
            description = paramDescription
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val mangaC by optionalLong {
            name = "manga_c"
            description = paramDescription
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val mangaD by optionalLong {
            name = "manga_d"
            description = paramDescription
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }
    }

    inner class EditArguments: Arguments() {
        val id by long {
            name = "manga"
            description = "El manga a editar."
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }

        val title by optionalString {
            name = "nuevonombre"
            description = "Nuevo tíutlo del manga"
            minLength = config.mangaTitleMinLength
            maxLength = config.mangaTitleMaxLength
        }

        val image by optionalAttachment {
            name = "imagen"
            description = "Nueva imagen del manga"
            validate {
                failIfNot("El archivo excede el límite de peso") {
                    value.isValidImage()
                }
            }
        }

        val description by optionalString {
            name = "descripción"
            description = "Nueva descripción"
            minLength = config.mangaDescMinLength
            maxLength = config.mangaDescMaxLength
        }

        val link by optionalString {
            name = "link"
            description ="Nuevo link donde comprar o leer el manga"
            maxLength = config.mangaLinkMaxLength

            validate {
                failIfNot("El link no es válido") {
                    value!!.isValidURL()
                }
            }
        }

        val volumes by optionalLong {
            name = "tomos"
            description = "Nueva cantidad de tomos"
            minValue = 1
        }

        val pagesPerVolume by optionalLong {
            name = "páginasportomo"
            description = "Nueva cantidad de páginas por tomo"
            minValue = 1
        }

        val chapters by optionalLong {
            name = "capítulos"
            description = "Nueva cantidad de capítulos"
            minValue = 1
        }

        val pagesPerChapter by optionalLong {
            name = "páginasporcapítulo"
            description = "Nueva cantidad de páginas por capítulo"
            minValue = 1
        }

        val demographic by optionalStringChoice {
            name = "demografía"
            description = "Nueva demografía del manga"
            demographics.forEach {
                choice(it, it)
            }
        }

        val addTags by optionalList(identity()) {
            name = "nuevostags"
            description = "Nuevos tags para este título"
            validate {
                failIf("No hay tags po sacowea") {
                    value!!.isEmpty()
                }
            }

            autoCompleteCallback = multipleTagsAutoCompletion
        }

        val removeTags by optionalList(identity()) {
            name = "removertags"
            description = "Tags a ser removidos"
            validate {
                failIf("No hay tags po sacowea") {
                    value!!.isEmpty()
                }
            }

            autoCompleteCallback = multipleTagsAutoCompletion
        }

        val unsetImage by optionalBoolean {
            name = "sacarimagen"
            description = "Elimina la imagen del manga."
        }
    }

    inner class DeletionArguments: Arguments() {
        val id by long {
            name = "manga"
            description = "Manga a eliminar"
            autoCompleteCallback = titleAutoCompletionIdNamePairs
        }
    }

    override suspend fun setup() {
        publicSlashCommand(::SearchMangaArgs) {
            name = "buscar"
            description = "Busca un manga por nombre, tag, y/o demografía"
            guild(config.guild)

            action {
                val criteria = with (arguments) { listOf(title, tagA, tagB, demographic) }
                val (title, tagA, tagB, demographic) = criteria

                if (criteria.all { it == null }) {
                    respond { content = "Debes especificar al menos un criterio." }
                    return@action
                }

                val filterDescription = (title?.let { "__Título__: $title\n" } ?: "") +
                        (tagA?.let { "__Tag A__: $tagA\n" } ?: "") +
                        (tagB?.let { "__Tag B__: $tagA\n" } ?: "") +
                        (demographic?.let { "__Demografía__: $demographic\n" } ?: "")

                val mangaList = db.searchManga(title, tagA, tagB, demographic, 15)

                @OptIn(EphemeralOrPublicView::class)
                when {
                    mangaList.isEmpty() -> respondWithInfo(
                            title="Sin resultados",
                            description="No se encontró nada similar a lo buscado:\n\n$filterDescription",
                            public=true,
                    )

                    mangaList.size == 1 -> respond {
                        embeds = mutableListOf(EmbedBuilder().mangaView(mangaList.first()))
                    }

                    mangaList.size <= 5 -> respondWithSuccess(
                            title="Encontrados ${mangaList.size} resultados\n\n",
                            description=filterDescription +
                                    mangaList.joinToString(prefix="\n", separator="\n") { "[#${it.id}] ${it.title}" },
                        )

                    else -> respondingPaginator {
                        val chunks = mangaList.chunked(5)

                        timeoutSeconds = 120
                        chunks.forEach { chunk ->
                            page {
                                this.title = "Encontrados ${mangaList.size} resultados\n\n"
                                description = filterDescription +
                                        chunk.joinToString(prefix="\n", separator="\n") { "[#${it.id}] ${it.title}" }
                                color = colors.success
                            }
                        }
                    }.send()
                }
            }
        }

        publicSlashCommand(::GroupMangaEntriesArguments) {
            name = "ver"
            description = "Muestra entradas de manga"
            guild(config.guild)

            action {
                val ids = listOfNotNull(
                        arguments.mangaA,
                        arguments.mangaB,
                        arguments.mangaC,
                        arguments.mangaD,
                ).toLongArray()

                val mangaList = db.getMangas(*ids).toList()

                @OptIn(EphemeralOrPublicView::class)
                when {
                    mangaList.isEmpty() -> respondWithInfo(
                            title="Sin resultados",
                            description="No se encontró nada similar a lo buscado",
                            public=true,
                    )

                    mangaList.size == 1 -> respond {
                        embeds = mutableListOf(EmbedBuilder().mangaView(mangaList.first()))
                    }

                    else -> respondingPaginator {
                        timeoutSeconds = 120
                        mangaList.map { it }.forEach { manga ->
                            page {
                                mangaView((manga))
                            }
                        }
                    }.send()
                }
            }
        }

        publicSlashCommand(::AddMangaArgs) {
            name = "agregar"
            description = "Agrega un manga con los datos proporcionados"
            guild(config.guild)

            check {
                hasRole(config.allowedRole)
            }
            
            action {
                try {
                    val imgURLSource: URL? = arguments.image?.let { URL(it.url) }

                    val insertedManga = db.addManga(
                        title = arguments.title,
                        description = arguments.description,
                        imgURLSource = imgURLSource,
                        link = arguments.link,
                        demographic = arguments.demographic,
                        volumes = arguments.volumes,
                        pagesPerVolume = arguments.pagesPerVolume,
                        chapters = arguments.chapters,
                        pagesPerChapter = arguments.pagesPerChapter,
                        tags = arguments.tags.toSet(),
                        read = false
                    )

                    kordLogger.info { "${user.id} added entry #${insertedManga.id} (${insertedManga.title})" }

                    respond {
                        content = "Agregado exitosamente."

                        embed {
                            mangaView(insertedManga)
                        }
                    }
                } catch (e: UnsupportedDownloadException) {
                    @OptIn(EphemeralOrPublicView::class)
                    respondWithError(
                            description="Ese tipo de imagen no es válido." +
                            " Prueba con una imagen más pequeña y en JPG o PNG.",
                            public=false,
                    )
                } catch (e: IOException) {
                    @OptIn(EphemeralOrPublicView::class)
                    respondWithError(description="Error al intentar descargar la imagen.", public=false)
                }
            }
        }

        ephemeralSlashCommand(::EditArguments) {
            name = "editar"
            description = "Modifica o elimina los campos de un manga"
            guild(config.guild)

            check {
                hasRole(config.allowedRole)
            }

            action {
                val flags = mutableListOf<UpdateFlags>()

                arguments.unsetImage?.let { flags.add(UpdateFlags.UNSET_IMG_URL) }

                val somethingChanged = arguments.args.any { it.converter.parsed != null && it.displayName != "id" }

                if (!somethingChanged) {
                    @OptIn(EphemeralOrPublicView::class)
                    respondWithError("No has especificado ningún cambio.")

                    return@action
                }

                val currentManga = db.getManga(arguments.id)

                currentManga ?: run {
                    @OptIn(EphemeralOrPublicView::class)
                    respondWithError("La id ${arguments.id} no pudo ser encontrada")

                    return@action
                }

                requestConfirmation(
                        "¿Confirmas la edición sobre ${currentManga.title}?",
                ) {
                    val requestedChanges = with(arguments) {
                        val tagsToAdd = addTags?.toSet()

                        val tagsToRemove = removeTags?.toSet()
                                ?.subtract(tagsToAdd ?: emptySet())
                                ?.takeIf(Set<*>::isNotEmpty)

                        MangaChanges(
                            id=id,
                            title=title,
                            description=description,
                            imgURLSource=arguments.image?.let { URL(it.url) },
                            link=link,
                            volumes=volumes,
                            pagesPerVolume=pagesPerVolume,
                            chapters=chapters,
                            pagesPerChapter=pagesPerChapter,
                            demographic=demographic,
                            tagsToAdd=tagsToAdd,
                            tagsToRemove=tagsToRemove,
                            read=null,
                        )
                    }

                    try {
                        val updatedManga = db.updateManga(requestedChanges, *flags.toTypedArray())
                        kordLogger.info { "${user.id} edited entry #${requestedChanges.id} (${currentManga.title})" }

                        respondWithChanges(currentManga, updatedManga, requestedChanges)
                    } catch (e: UnsupportedDownloadException) {
                        @OptIn(EphemeralOrPublicView::class)
                        respondWithError(
                                description="Ese tipo de imagen no es válido." +
                                        " Prueba con una imagen más pequeña y en JPG o PNG.",
                                public=false,
                        )
                    } catch (e: IOException) {
                        @OptIn(EphemeralOrPublicView::class)
                        respondWithError(description="Error al intentar descargar la imagen.", public=false)
                    }
                }
            }
        }

        ephemeralSlashCommand(::DeletionArguments) {
            name = "borrar"
            description = "Elimina una entrada de la base de datos"
            guild(config.guild)

            check {
                hasRole(config.allowedRole)
            }

            action {
                val manga = db.getManga(arguments.id)

                manga ?: run {
                    @OptIn(EphemeralOrPublicView::class)
                    respondWithError("La id ${arguments.id} no pudo ser encontrada")

                    return@action
                }

                requestConfirmation(
                    "¿Confirmas la eliminación de **${manga.title}**?",
                ) {
                    val successfullyDeleted = db.deleteManga(manga.id)

                    @OptIn(EphemeralOrPublicView::class)
                    if (successfullyDeleted)
                        channel.createEmbed {
                            user.asUserOrNull()?.let { u ->
                                author {
                                    name = "${u.username} (${u.id.value})"
                                    icon = u.avatar?.cdnUrl?.toUrl()
                                }
                            }
                            title = "Éxito"
                            description = "Eliminado **[#${manga.id}] ${manga.title}**"
                            color = colors.success
                        }
                    else this@action.respondWithError("No se pudo eliminar ${manga.title}")
                }
            }
        }
    }
}
