package ar.pelotude.ohhsugoi.util

import java.net.URL
import java.util.*

/** This isn't ideal but I'm not gonna add another dependency or write something fancy
 * for something meant to be used among trustworthy people */
fun String.isValidURL() = try {
    URL(this).toURI()
    true
} catch(e: java.net.MalformedURLException) {
    false
}

fun randomString(length: Int): String {
    val characters = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    return (0..length).map {
        characters.random()
    }.joinToString("")
}

fun uuidString() = UUID.randomUUID().toString()

fun String.capitalize() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/** But like in "This is a title", not "This Is A Title" */
fun String.makeTitle() = this.lowercase().capitalize()

inline fun <T> identity(): (T) -> T  = { it }
