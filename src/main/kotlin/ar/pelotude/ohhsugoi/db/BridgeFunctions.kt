package ar.pelotude.ohhsugoi.db

import com.kotlindiscord.kord.extensions.utils.getKoin
import io.ktor.http.*
import java.net.URL

val koin = getKoin()

internal fun storedImgURL(fileName: String): URL {
    val config: DatabaseConfiguration = koin.get()
    val webpage = config.webpage
    val subDirectory = config.mangaCoversUrlPath

    val url = URLBuilder(webpage)
            .appendPathSegments(subDirectory, fileName)
            .build()

    // TODO: Change URLs to ktor Urls here and everywhere else
    return URL(url.toString())
}

internal fun mangaSQLDmapper(
    id: Long, title: String, description: String, imgURL: String?, link: String?,
    demographic: String, volumes: Long?, pagesPerVolume: Long?, chapters: Long?,
    pagesPerChapter: Long?, read: Long, insertionDate: Long, authorId: Long?,
    deleted: Long, tags: String?
): MangaWithTags {
    return MangaWithTagsData(
        MangaData(
            id=id,
            title=title,
            description=description,
            imgURLSource=imgURL?.let { storedImgURL(it) },
            link=link,
            demographic=demographic,
            volumes=volumes,
            pagesPerVolume=pagesPerVolume,
            chapters=chapters,
            pagesPerChapter=pagesPerChapter,
            read=read.boolean,
            insertionDate=insertionDate,
        ),
        tags=tags?.toTagSet() ?: setOf()
    )
}

internal fun String.toTagSet() = this.split(',').toSet()

internal inline val Long.boolean: Boolean
    get() = this == 1L

internal fun Boolean.sqliteBool() = if (this) 1L else 0L