package ar.pelotude.ohhsugoi.bot

import ar.pelotude.ohhsugoi.db.UserData
import ar.pelotude.ohhsugoi.db.UsersDatabase
import ar.pelotude.ohhsugoi.db.scheduler.*
import com.kotlindiscord.kord.extensions.checks.hasRole
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.stringChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalRole
import com.kotlindiscord.kord.extensions.commands.converters.impl.role
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.components.forms.ModalForm
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.types.respondingPaginator
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.exception.EntityNotFoundException
import dev.kord.rest.builder.message.create.embed
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.launch
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class UtilsExtension<T : Any> : Extension(), KordExKoinComponent, SchedulerEventHandler<T> {
    override val name = "utils"

    private val scheduler: Scheduler<T> = get<Scheduler<T>>().apply { subscribe(this@UtilsExtension) }
    private val usersDatabase: UsersDatabase by inject()
    private val config: UtilsExtensionConfiguration by inject()

    private lateinit var loggerChannel: TextChannel

    private val availableZoneIds = ZoneId.getAvailableZoneIds().toSortedSet()

    // it might  be wiser to convert ULongs to RoleBehaviour and use their `.mention`
    private fun ULong?.toMentionOn(guild: Guild): String? {
        return when (this) {
            null -> null
            guild.id.value -> "@everyone"
            else -> "<@&$this>"
        }
    }

    inner class ScheduleArguments : Arguments() {
        val date by date {
            name = "fecha"
            description = "Fecha de envío. Formato día/mes/año hora:minuto. Puedes omitir el año o la fecha por completo."
        }

        val mention by optionalRole {
            name = "mención"
            description = "Rol al que mencionar."
        }
    }

    inner class ZoneIdArguments : Arguments() {
        val zoneId by string {
            name = "zona"
            description = "Tu zona horaria."

            maxLength = availableZoneIds.maxBy(String::length).length

            autoComplete {
                val typedIn = focusedOption.value
                val matches = availableZoneIds.asSequence().filter { it.contains(typedIn, true) }.take(10)

                suggestString {
                    matches.forEach { id -> choice(id, id) }
                }
            }

            validate {
                failIfNot(value in availableZoneIds, "La zona no es válida.")
            }
        }
    }

    inner class SearchScheduledMessagesArguments : Arguments() {
        val statusFilter by stringChoice {
            name = "estado"
            description = "Estado por el cual filtrar."

            choices(statusFilters)
        }
    }

    inner class DiscordMessageModal : ModalForm() {
        override var title: String = "Mensaje programado de Discord."

        val text= paragraphText {
            label = "Mensaje de Discord."
            maxLength = 1900
            minLength = 1
        }
    }

    open inner class ScheduledPostArguments : Arguments() {
        val postId by postId<T> {
            name = "id"
            description = "Id de la publicación."
        }
    }

    inner class EditDateArguments : ScheduledPostArguments() {
        val date by date {
            name = "fecha"
            description = "Nueva fecha."
        }
    }

    inner class EditMentionRoleArguments : ScheduledPostArguments() {
        val mention by role {
            name = "mención"
            description = "Rol al que mencionar."
        }
    }

    private val EVERYTHING = "EVERYTHING"

    private val statusFilters = mapOf(
        "pendiente" to Status.PENDING.name,
        "fallido" to Status.FAILED.name,
        "mandado" to Status.SENT.name,
        "cancelado" to Status.CANCELLED.name,
        "todo" to EVERYTHING
    )

    private fun statusFilterMap(name: String): Status? {
        return when (name) {
            EVERYTHING -> null
            else -> Status.valueOf(name)
        }
    }

    override suspend fun setup() {
        loggerChannel = kord.getChannelOf<TextChannel>(get(named("loggerChannel")))
            ?: throw EntityNotFoundException("Logger channel could not be found.")

        val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")

        ephemeralSlashCommand {
            name = "mensajes"
            description = "Administra mensajes automatizados."
            guild(config.guild)

            check {
                hasRole(config.schedulerRole)
            }

            publicSubCommand(::SearchScheduledMessagesArguments) {
                name = "buscar"
                description = "Lista los mensajes programados"

                action {
                    val posts = statusFilterMap(arguments.statusFilter).let { filter ->
                        scheduler.getPosts(filter)
                    }.sortedByDescending(ScheduledPostMetadataImpl<*>::execInstant)

                    if (posts.isEmpty()) {
                        @OptIn(EphemeralOrPublicView::class)
                        respondWithInfo("No se encontraron mensajes programados.")
                        return@action
                    }

                    respondingPaginator {
                        timeoutSeconds = 60

                        posts.chunked(5).forEach { postsChunk ->
                            owner = user

                            page {
                                title = "Encontrados ${posts.size} mensaje${if (posts.size > 1) 's' else ""} de Discord"

                                postsChunk.forEach { post ->
                                    val status = when (post.status) {
                                        Status.SENT -> "Enviado"
                                        Status.PENDING -> "Pendiente"
                                        Status.CANCELLED -> "Cancelado"
                                        Status.FAILED -> "Fallido"
                                    }

                                    field {
                                        name = "[#${post.id}] <t:${post.execInstant.epochSecond}:t> <t:${post.execInstant.epochSecond}:d> [$status]"
                                        // TODO: avoid printing multiple newlines
                                        value = (if (post.text.length >= 50) post.text.slice(0 until 47) + "..." else post.text)
                                        inline = false
                                    }
                                }
                                color = colors.success
                            }
                        }
                    }.send()
                }
            }

            publicSlashCommand(::ScheduleArguments, ::DiscordMessageModal) {
                name = "crear"
                description = "Programa un mensaje para mandar a Discord a cierta hora."

                action { modal ->
                    val dateInstant = arguments.date.toInstant()
                    val scheduledMsg = scheduler.schedule(modal!!.text.value!!, dateInstant, arguments.mention?.id?.value)

                    @OptIn(EphemeralOrPublicView::class)
                    respondWithSuccess("""Mensaje de Discord [#${scheduledMsg.id}] programado exitosamente
                        |
                        |Momento de envío: <t:${dateInstant.epochSecond}:R> (<t:${dateInstant.epochSecond}:F>)
                    """.trimMargin(), public=true)
                }
            }

            publicSlashCommand(::ScheduledPostArguments) {
                name = "ver"
                description = "Muestra detalles de un mensaje programado de Discord"

                action {
                    val post = scheduler.get(arguments.postId)

                    @OptIn(EphemeralOrPublicView::class)
                    if (post == null) respondWithError("No existe la publicación `${arguments.postId}`")
                    else respond {
                        val status = when (post.status) {
                            Status.SENT -> "Enviado"
                            Status.PENDING -> "Pendiente"
                            Status.CANCELLED -> "Cancelado"
                            Status.FAILED -> "Fallido"
                        }

                        embed {
                            title = "Mensaje de Discord [#${post.id}]"
                            description = post.text

                            field {
                                name = "Mención"
                                value = post.mentionId.toMentionOn(guild!!.asGuild()) ?: "Nadie"
                            }

                            field {
                                name = "Fecha de envío"
                                value = "<t:${post.execInstant.epochSecond}:F>"
                            }

                            field {
                                name = "Estado"
                                value = status
                            }
                        }
                    }
                }
            }

            publicSlashCommand(::ScheduledPostArguments) {
                name = "cancelar"
                description = "Cancela un mensaje programado de Discord"

                @OptIn(EphemeralOrPublicView::class)
                action {
                    if (scheduler.cancel(arguments.postId)) {
                        respondWithSuccess("Cancelado")
                    } else {
                        respondWithError("No se pudo cancelar ninguna publicación con esa id." +
                                    " Verifica que existe y que su estado esté pendiente de envío.")
                    }
                }
            }

            publicSlashCommand(::ScheduledPostArguments, ::DiscordMessageModal) {
                name = "editartexto"
                description = "Edita el texto de un mensaje programado de Discord"

                action {modal ->
                    val submittedEdition = modal!!.text.value!!

                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(arguments.postId, submittedEdition).let { success ->
                        if (success) respondWithSuccess("Editado el texto a:\n\n${submittedEdition}", public=true)
                        else {
                            val userGotDm = try {
                                user.getDmChannel().createMessage {
                                    embed {
                                        title = "Texto de edición fallida para #${arguments.postId}."
                                        description = submittedEdition
                                        color = colors.error
                                    }
                                }
                                true
                            } catch (e: RestRequestException) {
                                false
                            }

                            val description = "No se pudo cambiar el texto del mensaje #${arguments.postId}" + if (!userGotDm) {
                                ". Cópialo para evitar perderlo:\n\n${submittedEdition}"
                            } else {
                                ". Tu texto te fue envíado por mensaje directo."
                            }

                            respondWithError(description)
                        }
                    }
                }
            }

            publicSlashCommand(::EditDateArguments) {
                name = "editarfecha"
                description = "Edita la fecha de un mensaje programado de Discord"

                action {
                    val newExecInstant = arguments.date.toInstant()

                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(arguments.postId, newExecInstant=newExecInstant).let { success ->
                        if (success) {
                            respondWithSuccess("Fecha de publicación del mensaje #${arguments.postId} cambiada a <t:${newExecInstant.epochSecond}:F>.")
                        } else {
                            respondWithError("No se pudo cambiar la fecha de publicación del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }

            publicSlashCommand(::EditMentionRoleArguments) {
                name = "editarmención"
                description = "Edita el rol a mencionar en un mensaje."

                action {
                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.editPost(k=arguments.postId, newMentionRole=arguments.mention.id.value).let { success ->
                        if (success) {
                            respondWithSuccess("Rol a mencionar de #${arguments.postId} cambiado a ${arguments.mention.mention}.")
                        } else {
                            respondWithError("No se pudo cambiar el rol del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }

            publicSlashCommand(::ScheduledPostArguments) {
                name = "removermención"
                description = "Remueve el rol a mencionar en un mensaje."

                action {
                    @OptIn(EphemeralOrPublicView::class)
                    scheduler.removeMention(arguments.postId).let { success ->
                        if (success) {
                            respondWithSuccess("Rol a mencionar de la publicación #${arguments.postId} removido.")
                        } else {
                            respondWithError("No se pudo remover el rol del mensaje #${arguments.postId}.")
                        }
                    }
                }
            }
        }

        ephemeralSlashCommand {
            name = "zonahoraria"
            description = "Cambia o chequea tu zona horaria."
            guild(config.guild)

            ephemeralSubCommand(::ZoneIdArguments) {
                name = "cambiar"
                description = "Cambia tu zona horaria."

                action {
                    val zoneId = ZoneId.of(arguments.zoneId)
                    val userData = UserData(user.id.value, zoneId)
                    usersDatabase.setUser(userData)

                    @OptIn(EphemeralOrPublicView::class)
                    respondWithSuccess("""Zona cambiada a `${arguments.zoneId}`.
                    |
                    |Tu horario actual debería ser `${ZonedDateTime.now(zoneId).format(hourFormatter)}`
                    |""".trimMargin(), public=true)
                }
            }

            ephemeralSubCommand {
                name = "ver"
                description = "Mira cuál es tu zona horaria."

                action {
                    val zoneIdStr: String? = usersDatabase.getUser(user.id.value)?.zone?.toString()

                    if (zoneIdStr != null) {
                        val zoneId = ZoneId.of(zoneIdStr)

                        @OptIn(EphemeralOrPublicView::class)
                        respondWithInfo(
                            """Tu zona es `$zoneIdStr`
                            |
                            |Tu horario actual debería ser `${ZonedDateTime.now(zoneId).format(hourFormatter)}`
                            |""".trimMargin()
                        )
                    } else @OptIn(EphemeralOrPublicView::class) {
                        respondWithInfo("No tengo registrada tu zona horaria.")
                    }
                }
            }
        }
    }

    override fun handle(e: ScheduleEvent<T>) {
        kord.launch {
            if (e is Failure<T>) {
                loggerChannel.createMessage {
                    content="Hubo un problema mandando un mensaje: `<id=${e.post.id}, description=${e.reason}>`"
                }
            }
        }
    }
}